package com.example.demo.data

import com.example.demo.chat.Author
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One thread as the database held it before messages had rows of their own: every
 * message packed into [messages] as a single JSON text, a deleted one carrying
 * `"deleted":true` and the rest carrying no such key at all.
 *
 * Kept free of the database so the upgrade's one judgement — what a row from back then
 * says — can be tested without one. The migration runs this very function on every row.
 */
data class LegacyThreadRow(
    val id: String,
    val persona: String,
    val model: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: String,
)

/**
 * The same thread in today's rows, or null for a row that cannot be made sense of —
 * which is left behind alone, never taking the rest of the upgrade with it.
 */
fun LegacyThreadRow.toThreadRecordOrNull(): ThreadRecord? = runCatching {
    val old = Json.decodeFromString(legacyMessages, messages)
    // A message is told apart by its thread and its id now, so a row that uses one id
    // twice has no place to go — and trying anyway would fail the whole upgrade.
    require(old.map { it.id }.toSet().size == old.size)
    ThreadRecord(
        conversation = ConversationRecord(id, persona, model, createdAt, updatedAt),
        messages = old.mapIndexed { index, message ->
            MessageRecord(
                conversationId = id,
                id = message.id,
                position = index,
                // Carried over as written, but only if it is someone we know.
                author = Author.valueOf(message.author).name,
                text = message.text,
                createdAt = message.createdAt,
                deleted = message.deleted,
            )
        },
    )
}.getOrNull()

@Serializable
private data class LegacyMessage(
    val id: Long,
    val author: String,
    val text: String,
    val createdAt: Long,
    // Only ever written when true, so a row with nothing deleted looks exactly like one
    // from before messages could be deleted — and both mean nothing was deleted.
    val deleted: Boolean = false,
)

private val legacyMessages = ListSerializer(LegacyMessage.serializer())
