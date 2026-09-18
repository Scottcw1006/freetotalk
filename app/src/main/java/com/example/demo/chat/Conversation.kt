package com.example.demo.chat

import com.example.demo.llm.ModelSpec
import com.example.demo.llm.Turn
import com.example.demo.persona.Persona

enum class Author { You, Assistant }

data class ChatMessage(
    val id: Long,
    val author: Author,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** True while the assistant is still streaming this message in. */
    val streaming: Boolean = false,
    /**
     * Taken back by the reader, and restorable for as long as the thread exists. The
     * message keeps its place and its words; who skips it is decided by whoever is
     * reading — see [Conversation.title] and its neighbours.
     */
    val deleted: Boolean = false,
)

/** What a long press on a message offers. */
enum class MessageAction { Copy, Delete }

/**
 * A reply still arriving offers nothing: what would be copied or deleted is not settled
 * yet. A deleted message offers nothing either — its one way back is the restore link.
 * This asks about the message alone, so a reply streaming in elsewhere in the thread
 * takes nothing away from the finished ones.
 */
val ChatMessage.longPressActions: Set<MessageAction>
    get() = if (streaming || deleted) emptySet() else setOf(MessageAction.Copy, MessageAction.Delete)

/**
 * The words as stored, not as drawn: a reply keeps its Markdown marks, and an ending
 * mark such as "…（已停止）" comes along because it is part of the text.
 */
val ChatMessage.clipboardText: String
    get() = text

/** Anything that stands for one thread, whole or not. */
interface HasThreadId {
    val id: String
}

/**
 * One row of the history list: what it shows of a saved thread, and nothing else of it.
 * The thread itself is read when it is opened.
 */
data class HistoryEntry(
    override val id: String,
    val persona: Persona,
    val model: ModelSpec,
    val updatedAt: Long,
    val title: String,
    val preview: String,
) : HasThreadId

/**
 * One chat thread. A conversation is bound to both the persona and the model it was
 * started with — switching either begins a new one, so a thread never mixes two voices
 * or two models, and the history list can honestly label what produced each answer.
 */
data class Conversation(
    override val id: String,
    val persona: Persona,
    val model: ModelSpec,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage>,
) : HasThreadId {
    /**
     * Threads are named after whatever the user opened with — of what is still there.
     * A thread whose every line from the user was deleted is not one they never spoke in,
     * and does not get to be called that.
     */
    val title: String
        get() = messages.firstOrNull { it.author == Author.You && !it.deleted }
            ?.text?.replace('\n', ' ')?.cutTo(26)
            ?: if (isBlank) "還沒說話" else "（訊息已刪除）"

    val preview: String
        get() = messages.lastOrNull { !it.deleted }?.text?.replace('\n', ' ')?.cutTo(40).orEmpty()

    /**
     * A cut short enough to fit on one line gets no ellipsis from the drawer row,
     * so the cut itself carries the "…" whenever something was dropped.
     */
    private fun String.cutTo(limit: Int): String {
        if (length <= limit) return this
        // A cut inside a character — half a surrogate pair, or the first code point of 👍🏽
        // — leaves something unreadable in front of the "…" (and a half pair is a string
        // uiautomator refuses to dump). Step back to where the character starts; a single
        // character longer than the whole limit is kept whole instead of leaving a bare "…".
        val end = characterBoundaryAtOrBefore(limit).takeIf { it > 0 } ?: characterBoundaryAtOrAfter(limit)
        return if (end >= length) this else take(end) + "…"
    }

    /**
     * An untouched thread is not worth keeping in the history list. Deleted messages
     * count here, unlike everywhere else: a blank thread is removed from the phone when
     * saved, and that would take every deleted message's words — and the way back to
     * them — with it.
     */
    val isBlank: Boolean
        get() = messages.none { it.author == Author.You }
}

/**
 * Whether two versions of a thread say the same thing: the same messages, in the same
 * order, with the same words, each still there or not. Deliberately blind to
 * [ChatMessage.streaming] — a reply that has stopped arriving contains exactly the text
 * it already contained.
 */
internal fun Conversation.hasSameContentAs(other: Conversation): Boolean =
    messages.size == other.messages.size &&
        messages.indices.all { index ->
            messages[index].id == other.messages[index].id &&
                messages[index].text == other.messages[index].text &&
                messages[index].deleted == other.messages[index].deleted
        }

/**
 * What the model is shown of this thread when the next line is sent. Deleted messages go
 * first and leading assistant turns second: the persona's opener is scene-setting, not
 * dialogue, and so is a reply left at the front because the line it answered was deleted
 * — Gemma's format folds the system prompt into whatever turn comes first.
 */
fun Conversation.contextForNextTurn(): List<Turn> =
    messages
        .filterNot { it.deleted }
        .map { Turn(fromUser = it.author == Author.You, text = it.text) }
        .dropWhile { !it.fromUser }

fun Conversation.withMessageDeleted(messageId: Long, deleted: Boolean): Conversation =
    copy(messages = messages.map { if (it.id == messageId) it.copy(deleted = deleted) else it })

sealed interface EngineStatus {
    /** Unpacking the bundled model on first launch. [progress] is 0f..1f. */
    data class Extracting(val progress: Float) : EngineStatus
    /** Model file is in place; the inference engine is spinning up. */
    data object Starting : EngineStatus
    data object Ready : EngineStatus
    data class Failed(val message: String) : EngineStatus
}

/** Something about the saved threads the user needs to be told. */
enum class StorageNotice(val message: String) {
    StartedOver("之前的對話紀錄讀不出來，已經清空，從這裡重新開始。"),
    SaveFailed("有一段對話沒能存下來。"),
    CannotOpen("目前讀不到之前的對話紀錄（紀錄沒有被刪除），這段期間的對話也可能存不下來。"),
}

data class ChatUiState(
    val conversation: Conversation,
    val history: List<HistoryEntry> = emptyList(),
    val availableModels: List<ModelSpec> = emptyList(),
    val status: EngineStatus = EngineStatus.Extracting(0f),
    val isReplying: Boolean = false,
    val search: SearchUiState = SearchUiState(),
    /** Waiting to be shown, oldest first. */
    val notices: List<StorageNotice> = emptyList(),
    /** The saved threads could not be opened this launch, so [history] is empty for that reason. */
    val historyUnavailable: Boolean = false,
) {
    val canSend: Boolean get() = status is EngineStatus.Ready && !isReplying
}
