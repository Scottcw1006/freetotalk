package com.example.demo.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.demo.chat.Author
import com.example.demo.chat.ChatMessage
import com.example.demo.chat.Conversation
import com.example.demo.llm.ModelSpec
import com.example.demo.persona.Persona
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One thread as the database holds it: one row, with every message packed into
 * [messages] as a single JSON text.
 *
 * One row per thread is what keeps a save to a single statement. A save that took two —
 * clear the old messages, then write the new ones — would leave a moment in which a
 * reader finds the thread missing or half-written, which is exactly how threads used to
 * vanish from the history list when two saves of the same thread overlapped. Nothing
 * here needs to query individual messages: search runs over what is in memory.
 *
 * Every column is a plain string or number, so Room can always hand a row over. Whether
 * that row makes sense is decided afterwards by [ConversationReader], one row at a time.
 */
@Entity(tableName = "conversations")
data class ConversationRecord(
    @PrimaryKey val id: String,
    val persona: String,
    val model: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: String,
)

/** What one save amounts to once the rules about blank threads have been applied. */
sealed interface SaveAction {
    data class Write(val record: ConversationRecord) : SaveAction
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

fun Conversation.toRecord(): ConversationRecord = ConversationRecord(
    id = id,
    persona = persona.name,
    model = model.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    messages = json.encodeToString(storedMessages, messages.map { it.toStored() }),
)

/**
 * The one reading rule, shared by "every thread" and "the thread with this id", so the
 * two can never disagree about whether a thread exists.
 *
 * Text comes back exactly as it went in. Model output is repaired while it streams in,
 * before it is ever saved; repairing again on the way out would also rewrite what the
 * user typed — a literal `\n` would turn into a line break after a restart.
 */
object ConversationReader {

    /** Newest first. A row that cannot be read is left out, and only that row. */
    fun all(records: List<ConversationRecord>): List<Conversation> =
        records.mapNotNull { it.toConversationOrNull() }.sortedByDescending { it.updatedAt }

    fun find(records: List<ConversationRecord>, id: String): Conversation? =
        all(records).firstOrNull { it.id == id }

    private fun ConversationRecord.toConversationOrNull(): Conversation? = runCatching {
        Conversation(
            id = id,
            persona = Persona.of(persona),
            model = ModelSpec.of(model),
            createdAt = createdAt,
            updatedAt = updatedAt,
            messages = json.decodeFromString(storedMessages, messages).map { it.toMessage() },
        )
    }.getOrNull()
}

@Serializable
private data class StoredMessage(
    val id: Long,
    val author: String,
    val text: String,
    val createdAt: Long,
)

// A message is only ever saved as "still streaming" by accident of timing; what is
// stored is the text it held, never the typing indicator.
private fun ChatMessage.toStored() = StoredMessage(id, author.name, text, createdAt)

private fun StoredMessage.toMessage() = ChatMessage(
    id = id,
    author = Author.valueOf(author),
    text = text,
    createdAt = createdAt,
)

private val storedMessages = ListSerializer(StoredMessage.serializer())

private val json = Json
