package com.example.demo.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.demo.chat.Author
import com.example.demo.chat.ChatMessage
import com.example.demo.chat.Conversation
import com.example.demo.llm.ModelSpec
import com.example.demo.persona.Persona

/**
 * One thread as the database holds it: a row of its own facts here, and one row per
 * message in [MessageRecord]. Nothing about a message lives anywhere but its own row.
 *
 * Every column is a plain string or number, so Room can always hand a row over. Whether
 * the rows make sense is decided afterwards by [ConversationReader], one thread at a time.
 */
@Entity(tableName = "conversations")
data class ConversationRecord(
    @PrimaryKey val id: String,
    val persona: String,
    val model: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * One message. It is told apart by the thread it belongs to together with [id]: ids start
 * again from 0 in every thread, so on their own they collide across threads.
 *
 * [position] is what puts a thread's messages in order. Ids the app hands out only ever
 * grow, but a thread that reached the store from outside may number them any way it
 * likes, and the order it was written in is the order it is shown in.
 */
@Entity(tableName = "messages", primaryKeys = ["conversationId", "id"])
data class MessageRecord(
    val conversationId: String,
    val id: Long,
    val position: Int,
    val author: String,
    val text: String,
    val createdAt: Long,
    val deleted: Boolean,
)

/** A thread's row together with its message rows, in order. */
data class ThreadRecord(
    val conversation: ConversationRecord,
    val messages: List<MessageRecord>,
)

/** What one save amounts to once the rules about blank threads have been applied. */
sealed interface SaveAction {
    data class Write(val record: ThreadRecord) : SaveAction
    data class Delete(val id: String) : SaveAction
}

/**
 * A thread the user never wrote in is noise in the history list, so saving one removes
 * whatever is stored under its id instead of storing it. That includes a thread that
 * reached the store from outside carrying only assistant messages: opening it and
 * leaving clears it.
 */
fun Conversation.toSaveAction(): SaveAction =
    if (isBlank) SaveAction.Delete(id) else SaveAction.Write(toRecord())

// A message is only ever saved as "still streaming" by accident of timing; what is
// stored is the text it held, never the typing indicator.
fun Conversation.toRecord(): ThreadRecord = ThreadRecord(
    conversation = ConversationRecord(
        id = id,
        persona = persona.name,
        model = model.name,
        createdAt = createdAt,
        updatedAt = updatedAt,
    ),
    messages = messages.mapIndexed { index, message ->
        MessageRecord(
            conversationId = id,
            id = message.id,
            position = index,
            author = message.author.name,
            text = message.text,
            createdAt = message.createdAt,
            deleted = message.deleted,
        )
    },
)

/**
 * The one reading rule, shared by every way of getting threads out of the store — all of
 * them, the one with this id, a batch for searching — so they can never disagree about
 * whether a thread exists.
 *
 * Text comes back exactly as it went in. Model output is repaired while it streams in,
 * before it is ever saved; repairing again on the way out would also rewrite what the
 * user typed — a literal `\n` would turn into a line break after a restart.
 */
object ConversationReader {

    /** Newest first. A thread that cannot be read is left out, and only that thread. */
    fun all(records: List<ThreadRecord>): List<Conversation> =
        records.mapNotNull { it.toConversationOrNull() }.sortedByDescending { it.updatedAt }

    fun find(records: List<ThreadRecord>, id: String): Conversation? =
        all(records).firstOrNull { it.id == id }
}

// The author is parsed here rather than by Room: one row from nobody we know would
// otherwise fail the whole query it came back in, not just its own thread.
private fun ThreadRecord.toConversationOrNull(): Conversation? = runCatching {
    Conversation(
        id = conversation.id,
        persona = Persona.of(conversation.persona),
        model = ModelSpec.of(conversation.model),
        createdAt = conversation.createdAt,
        updatedAt = conversation.updatedAt,
        messages = messages.sortedBy { it.position }.map { it.toMessage() },
    )
}.getOrNull()

private fun MessageRecord.toMessage() = ChatMessage(
    id = id,
    author = Author.valueOf(author),
    text = text,
    createdAt = createdAt,
    deleted = deleted,
)
