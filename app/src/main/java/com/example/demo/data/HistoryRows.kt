package com.example.demo.data

import com.example.demo.chat.Author
import com.example.demo.chat.ChatMessage
import com.example.demo.chat.Conversation
import com.example.demo.chat.HistoryEntry
import com.example.demo.llm.ModelSpec
import com.example.demo.persona.Persona

/**
 * What one query brings back for one row of the history list: the thread's own facts and
 * the two messages a title and a preview can come from, instead of every message it has.
 */
data class HistoryRow(
    val id: String,
    val persona: String,
    val model: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** The first thing the user said that is still there. */
    val firstSaid: String?,
    /** The last message that is still there, whoever said it. */
    val lastShown: String?,
    /** Whether the user ever said anything, deleted or not. */
    val everSaid: Boolean,
    /** A thread with a message from nobody we know cannot be opened, so it is not listed. */
    val hasUnknownAuthor: Boolean,
)

/** Newest first, leaving out the threads that could not be opened if they were tapped. */
fun List<HistoryRow>.toHistory(): List<HistoryEntry> =
    mapNotNull { it.toEntryOrNull() }.sortedByDescending { it.updatedAt }

/**
 * The title and the preview are not worked out here. The row is stood up as the smallest
 * thread that says the same things about itself — the same first line from the user, the
 * same last message, the same answer to "did they ever speak" — and that thread is asked,
 * so [Conversation.title] and [Conversation.preview] stay the only place those rules live.
 */
fun HistoryRow.toEntryOrNull(): HistoryEntry? {
    if (hasUnknownAuthor) return null
    val standIn = Conversation(
        id = id,
        persona = Persona.of(persona),
        model = ModelSpec.of(model),
        createdAt = createdAt,
        updatedAt = updatedAt,
        messages = listOfNotNull(
            ChatMessage(0, Author.You, "", createdAt, deleted = true).takeIf { everSaid },
            firstSaid?.let { ChatMessage(1, Author.You, it, createdAt) },
            lastShown?.let { ChatMessage(2, Author.Assistant, it, createdAt) },
        ),
    )
    return HistoryEntry(
        id = id,
        persona = standIn.persona,
        model = standIn.model,
        updatedAt = updatedAt,
        title = standIn.title,
        preview = standIn.preview,
    )
}
