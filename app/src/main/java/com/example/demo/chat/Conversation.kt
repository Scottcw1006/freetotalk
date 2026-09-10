package com.example.demo.chat

import com.example.demo.llm.ModelSpec
import com.example.demo.persona.Persona

enum class Author { You, Assistant }

data class ChatMessage(
    val id: Long,
    val author: Author,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** True while the assistant is still streaming this message in. */
    val streaming: Boolean = false,
)

/**
 * One chat thread. A conversation is bound to both the persona and the model it was
 * started with — switching either begins a new one, so a thread never mixes two voices
 * or two models, and the history list can honestly label what produced each answer.
 */
data class Conversation(
    val id: String,
    val persona: Persona,
    val model: ModelSpec,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage>,
) {
    /** Threads are named after whatever the user opened with. */
    val title: String
        get() = messages.firstOrNull { it.author == Author.You }
            ?.text?.replace('\n', ' ')?.take(26)
            ?: "還沒說話"

    val preview: String
        get() = messages.lastOrNull()?.text?.replace('\n', ' ')?.take(40).orEmpty()

    /** An untouched thread is not worth keeping in the history list. */
    val isBlank: Boolean
        get() = messages.none { it.author == Author.You }
}

/**
 * Whether two versions of a thread say the same thing: the same messages, in the same
 * order, with the same words. Deliberately blind to [ChatMessage.streaming] — a reply
 * that has stopped arriving contains exactly the text it already contained.
 */
internal fun Conversation.hasSameContentAs(other: Conversation): Boolean =
    messages.size == other.messages.size &&
        messages.indices.all { index ->
            messages[index].id == other.messages[index].id &&
                messages[index].text == other.messages[index].text
        }

sealed interface EngineStatus {
    /** Unpacking the bundled model on first launch. [progress] is 0f..1f. */
    data class Extracting(val progress: Float) : EngineStatus
    /** Model file is in place; the inference engine is spinning up. */
    data object Starting : EngineStatus
    data object Ready : EngineStatus
    data class Failed(val message: String) : EngineStatus
}

data class ChatUiState(
    val conversation: Conversation,
    val history: List<Conversation> = emptyList(),
    val availableModels: List<ModelSpec> = emptyList(),
    val status: EngineStatus = EngineStatus.Extracting(0f),
    val isReplying: Boolean = false,
    val search: SearchUiState = SearchUiState(),
) {
    val canSend: Boolean get() = status is EngineStatus.Ready && !isReplying

    /**
     * [history] leaves out the open thread on purpose, which is right for a list that
     * claims to say how many threads exist — but it means "all of them" is a thing the
     * app cannot otherwise say. Search is the first caller that needs it, and the open
     * thread is the one most likely to be searched for.
     *
     * This is a merge of two sources that can in principle describe the same thread:
     * [history] is what was loaded from disk, [conversation] is the live one. Today they
     * never overlap — refreshing history filters the open thread out — but that holds
     * only because of the order two state updates happen in, which is not something the
     * next person to touch thread switching would know they were relying on. So the open
     * thread goes first and the merge de-duplicates: when the same thread does arrive
     * twice, the right one to keep is the in-memory copy, because it may hold messages
     * that were never saved, or a reply still streaming in. The disk is never newer.
     */
    val allThreads: List<Conversation>
        get() = (listOf(conversation) + history)
            .distinctBy { it.id }
            .sortedByDescending { it.updatedAt }
}
