package com.example.demo.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.demo.data.ConversationRepository
import com.example.demo.data.SessionPreferences
import com.example.demo.llm.LlmEngine
import com.example.demo.llm.ModelSpec
import com.example.demo.llm.ModelStore
import com.example.demo.persona.Persona
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * A safety valve, not a style rule. SmolLM never emits its stop token, so left alone it
 * generates until the KV cache is full — measured at 1208 tokens and 29 seconds for a
 * one-line question. The personas already ask for short answers; this only catches a
 * model that has stopped listening.
 *
 * The comparison screen deliberately has no cap: a model that cannot stop is exactly the
 * kind of thing you want to see when comparing them.
 */
private const val REPLY_CHARACTER_CAP = 800

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = LlmEngine.get(application)
    private val repository = ConversationRepository.get(application)
    private val session = SessionPreferences(application)
    private val available = ModelStore.installed(application)

    /** A remembered model whose asset is no longer bundled must not strand the app. */
    private val rememberedModel: ModelSpec
        get() = session.lastModel.takeIf { it in available }
            ?: available.firstOrNull()
            ?: ModelSpec.Default

    private val _uiState = MutableStateFlow(
        ChatUiState(
            conversation = newThread(session.lastPersona, rememberedModel),
            availableModels = available,
        )
    )
    /**
     * Search results are derived on the way out rather than written in, so there is
     * exactly one place they can be computed and no way for a state change to forget to
     * recompute them. Deleting a thread or streaming a reply in changes what matches, and
     * neither of those goes anywhere near the search code.
     */
    val uiState: StateFlow<ChatUiState> = _uiState
        .map { it.withSearchResults() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value.withSearchResults())

    private var replyJob: Job? = null
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            // Before anything reads the store: the old per-thread files are not carried over.
            repository.removeLegacyThreads()
            // Land back on the exact thread the user left, not merely the newest one.
            // If that thread was never written in it was never saved, and the blank
            // thread built above — already carrying the remembered model — stands in
            // for it. Where the user was is left as it was either way: a blank thread's
            // id is worth nothing after a restart.
            val resumed = session.lastThreadId?.let { id -> repository.find(id) }
            if (resumed != null) {
                _uiState.update { it.copy(conversation = resumed) }
            }
            loadModel(_uiState.value.conversation.model)
        }
        viewModelScope.launch {
            val openId = _uiState.map { it.conversation.id }.distinctUntilChanged()
            historyOf(repository.all, openId).collect { history ->
                _uiState.update { state ->
                    // Filtered once more against the thread open right now, in case it
                    // changed after this list was worked out; the next list follows anyway.
                    state.copy(history = history.filterNot { it.id == state.conversation.id })
                }
            }
        }
        viewModelScope.launch {
            // Said once, and the drawer keeps saying it: an empty history list would
            // otherwise read as "everything is gone" long after the notice has faded.
            repository.cannotOpen.first { it }
            _uiState.update { it.copy(historyUnavailable = true) }
            notify(StorageNotice.CannotOpen)
        }
        viewModelScope.launch {
            repository.wasReset.collect { reset ->
                if (reset) {
                    notify(StorageNotice.StartedOver)
                    repository.acknowledgeReset()
                }
            }
        }
    }

    // ---- model ---------------------------------------------------------------

    private fun loadModel(spec: ModelSpec) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(status = EngineStatus.Extracting(0f)) }
            try {
                engine.load(spec) { progress ->
                    _uiState.update {
                        // Once the copy is done the engine still has to load weights,
                        // which has no progress signal of its own.
                        val stage = if (progress >= 1f) {
                            EngineStatus.Starting
                        } else {
                            EngineStatus.Extracting(progress)
                        }
                        it.copy(status = stage)
                    }
                }
                _uiState.update { it.copy(status = EngineStatus.Ready) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(status = EngineStatus.Failed(e.message ?: e.toString()))
                }
            }
        }
    }

    /**
     * Switching model starts a fresh thread, same as switching persona. A thread that
     * changed model halfway through could not honestly be labelled in the history list,
     * and comparing two models is what the comparison screen is for.
     */
    fun switchModel(spec: ModelSpec) {
        if (spec == _uiState.value.conversation.model) return
        stop()
        viewModelScope.launch {
            persist(_uiState.value.conversation)
            _uiState.update {
                it.copy(conversation = newThread(it.conversation.persona, spec))
            }
            rememberSession()
            loadModel(spec)
        }
    }

    // ---- threads -------------------------------------------------------------

    /**
     * Switching persona always starts a fresh thread. A conversation carries exactly one
     * voice, so history can never show a thread that changes character halfway through.
     */
    fun switchPersona(persona: Persona) {
        stop()
        viewModelScope.launch {
            persist(_uiState.value.conversation)
            _uiState.update {
                it.copy(conversation = newThread(persona, it.conversation.model))
            }
            rememberSession()
        }
    }

    fun startNewThread() = switchPersona(_uiState.value.conversation.persona)

    fun openThread(id: String) {
        if (id == _uiState.value.conversation.id) return
        stop()
        viewModelScope.launch {
            persist(_uiState.value.conversation)
            val opened = repository.find(id) ?: return@launch
            _uiState.update { it.copy(conversation = opened) }
            rememberSession()
            loadModel(opened.model)
        }
    }

    fun deleteThread(id: String) {
        viewModelScope.launch {
            repository.delete(id)
            if (id == _uiState.value.conversation.id) {
                _uiState.update {
                    it.copy(conversation = newThread(it.conversation.persona, it.conversation.model))
                }
                rememberSession()
            }
        }
    }

    // ---- searching -----------------------------------------------------------

    fun onSearchQueryChange(text: String) {
        _uiState.update { it.copy(search = it.search.copy(query = text.withoutLeadingSpace())) }
    }

    /** A trailing space belongs to a word still being typed; once the caret leaves, it does not. */
    fun onSearchFocusLost() {
        _uiState.update { state ->
            state.copy(search = state.search.copy(query = state.search.query.withoutTrailingSpace()))
        }
    }

    fun clearSearch() {
        if (_uiState.value.search.query.isEmpty()) return
        _uiState.update { it.copy(search = SearchUiState()) }
    }

    /**
     * Matching runs over what is in memory, never over what reached the disk. The open
     * thread may not be saved yet — it may never be, if the user has not written in it —
     * and a reply still streaming in exists nowhere else at all.
     */
    private fun ChatUiState.withSearchResults(): ChatUiState = when {
        !search.isActive ->
            if (search.results.isEmpty()) this else copy(search = search.copy(results = emptyList()))
        else ->
            copy(search = search.copy(results = searchConversations(search.query, allThreads)))
    }

    private fun rememberSession() {
        val thread = _uiState.value.conversation
        session.remember(thread.id, thread.model, thread.persona)
    }

    /**
     * Saves go through here so that a write that did not happen is said out loud. The
     * thread stays on screen either way; only the copy on the phone is missing.
     */
    private suspend fun persist(conversation: Conversation) {
        if (!repository.save(conversation)) notify(StorageNotice.SaveFailed)
    }

    private fun notify(notice: StorageNotice) {
        _uiState.update { it.copy(notices = it.notices + notice) }
    }

    /** The screen has finished showing [notice]. */
    fun noticeShown(notice: StorageNotice) {
        _uiState.update { state ->
            val index = state.notices.indexOf(notice)
            if (index < 0) state else state.copy(notices = state.notices.filterIndexed { i, _ -> i != index })
        }
    }

    private fun newThread(persona: Persona, model: ModelSpec): Conversation {
        val now = System.currentTimeMillis()
        return Conversation(
            id = UUID.randomUUID().toString(),
            persona = persona,
            model = model,
            createdAt = now,
            updatedAt = now,
            messages = listOf(ChatMessage(0, Author.Assistant, persona.opener, now)),
        )
    }

    // ---- talking -------------------------------------------------------------

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || !_uiState.value.canSend) return

        val thread = _uiState.value.conversation
        // Everything already said, up to but not including this message.
        val context = thread.contextForNextTurn()

        val nextId = (thread.messages.maxOfOrNull { it.id } ?: -1L) + 1
        val replyId = nextId + 1
        updateThread { current ->
            current.copy(
                messages = current.messages + listOf(
                    ChatMessage(nextId, Author.You, prompt),
                    ChatMessage(replyId, Author.Assistant, "", streaming = true),
                ),
            )
        }
        _uiState.update { it.copy(isReplying = true) }

        replyJob = viewModelScope.launch {
            val answer = StringBuilder()
            var ending: ReplyEnding = ReplyEnding.Complete
            try {
                engine.reply(thread.model, thread.persona.systemPrompt, context, prompt)
                    // emit() suspends until the collector below has appended the
                    // fragment, so `answer` is already up to date when the cap is
                    // checked. Unwinding the flow cancels generation through awaitClose.
                    .transformWhile { chunk ->
                        emit(chunk)
                        if (chunk is LlmEngine.ReplyChunk.Partial) {
                            ending = when {
                                answer.isLoopingOnItself() -> ReplyEnding.RepetitionLoop
                                answer.length >= REPLY_CHARACTER_CAP -> ReplyEnding.LengthCap
                                else -> ReplyEnding.Complete
                            }
                        }
                        ending == ReplyEnding.Complete
                    }
                    .collect { chunk ->
                        when (chunk) {
                            is LlmEngine.ReplyChunk.Partial -> {
                                answer.append(chunk.text)
                                updateMessage(
                                    replyId,
                                    answer.toString().repairModelText(),
                                    streaming = true,
                                )
                            }
                            // Trust the one-pass decode over the fragments shown so far.
                            is LlmEngine.ReplyChunk.Complete -> {
                                answer.setLength(0)
                                answer.append(chunk.text)
                            }
                        }
                    }
            } catch (e: CancellationException) {
                // stop() has already settled this reply and written the thread it
                // belonged to. Nothing is left to do here — and by now the thread this
                // job started on may no longer be the open one, so touching state would
                // land on the wrong conversation.
                throw e
            } catch (e: Exception) {
                ending = ReplyEnding.Failed(e.message ?: e.toString())
            }
            settle(replyId, answer.toString().repairModelText(), ending)
        }
    }

    /**
     * Write down what the reply ended up holding, then persist it. Every ending except
     * the user pressing stop arrives here; that one is settled in [stop] itself, for the
     * reason spelled out there.
     */
    private suspend fun settle(replyId: Long, streamed: String, ending: ReplyEnding) {
        when (val outcome = finalReply(streamed, ending)) {
            ReplyOutcome.Drop -> updateThread { current ->
                current.copy(messages = current.messages.filterNot { it.id == replyId })
            }
            is ReplyOutcome.Keep -> updateMessage(replyId, outcome.text, streaming = false)
        }
        _uiState.update { it.copy(isReplying = false) }
        persist(_uiState.value.conversation)
        rememberSession()
    }

    /**
     * "That's enough" — which is not the same as "this went wrong". The reply keeps the
     * text the reader was looking at when they pressed it, plus a mark saying it was cut
     * short, and that result reaches the disk here rather than whenever they happen to
     * do something else next.
     *
     * Settling happens in this function rather than where the reply job unwinds, and the
     * reason is [openThread] and its two siblings: they call this and then switch to
     * another thread. A cancelled job runs its last block some time later, by which
     * point the thread the reply belonged to may no longer be the open one — and the
     * partial reply would be appended to whichever thread is.
     */
    fun stop() {
        replyJob?.cancel()
        replyJob = null
        _uiState.update { it.copy(isReplying = false) }

        val streaming = _uiState.value.conversation.messages.lastOrNull { it.streaming }
            ?: return
        updateThread { current ->
            when (val outcome = finalReply(streaming.text, ReplyEnding.UserStopped)) {
                // Nothing had arrived yet, so there is no answer to keep — but what the
                // reader said stays.
                ReplyOutcome.Drop ->
                    current.copy(messages = current.messages.filterNot { it.id == streaming.id })
                is ReplyOutcome.Keep -> current.copy(
                    messages = current.messages.map {
                        if (it.id == streaming.id) {
                            it.copy(text = outcome.text, streaming = false)
                        } else {
                            it
                        }
                    },
                )
            }
        }
        // The save that used to sit in the reply job never ran: it suspends, and a
        // cancelled job's suspending calls return without doing anything. So a stopped
        // reply only ever reached the disk if the reader went on to do something that
        // saved for its own reasons. This coroutine is started outside that cancelled
        // job, which is the whole difference.
        val stopped = _uiState.value.conversation
        viewModelScope.launch {
            persist(stopped)
        }
    }

    // ---- deleting ------------------------------------------------------------

    fun deleteMessage(id: Long) = setMessageDeleted(id, deleted = true)

    fun restoreMessage(id: Long) = setMessageDeleted(id, deleted = false)

    /**
     * Saved on the spot, through the same door as every other save: nothing else is
     * bound to happen after a delete, so there is no later save for it to ride along
     * with. A thread nobody wrote in is still not stored — [persist] sees to that.
     */
    private fun setMessageDeleted(id: Long, deleted: Boolean) {
        val target = _uiState.value.conversation.messages.firstOrNull { it.id == id } ?: return
        if (target.deleted == deleted) return
        if (deleted && MessageAction.Delete !in target.longPressActions) return
        updateThread { it.withMessageDeleted(id, deleted) }
        val changed = _uiState.value.conversation
        viewModelScope.launch { persist(changed) }
    }

    private fun updateMessage(id: Long, text: String, streaming: Boolean) {
        updateThread { current ->
            current.copy(
                messages = current.messages.map {
                    if (it.id == id) it.copy(text = text, streaming = streaming) else it
                },
            )
        }
    }

    /**
     * The single place that decides a thread has been updated. The time follows the
     * content: if this change left the same messages saying the same words — clearing the
     * "still streaming" flag, for instance — then nothing was updated and the clock stays
     * where it was. Otherwise a thread you merely switched away from would climb to the
     * top of every list that orders by this, and "most recent" would come to mean "most
     * recently glanced at".
     */
    private fun updateThread(transform: (Conversation) -> Conversation) {
        _uiState.update { state ->
            val updated = transform(state.conversation)
            val stamped = if (updated.hasSameContentAs(state.conversation)) {
                updated
            } else {
                updated.copy(updatedAt = System.currentTimeMillis())
            }
            state.copy(conversation = stamped)
        }
    }

    // The engine is shared with the comparison screen and outlives any one ViewModel,
    // so it is deliberately not closed here.
}
