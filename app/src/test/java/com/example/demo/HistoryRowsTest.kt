package com.example.demo

import com.example.demo.chat.Author
import com.example.demo.chat.ChatMessage
import com.example.demo.chat.Conversation
import com.example.demo.data.HistoryRow
import com.example.demo.data.toEntryOrNull
import com.example.demo.data.toHistory
import com.example.demo.llm.ModelSpec
import com.example.demo.persona.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The history list no longer holds whole threads, only what one query brings back for
 * each. Whatever it shows must still be what the whole thread would have said of itself.
 *
 * What these cannot see is the query: whether it really picks the first line still there
 * and the last message still there. That half is only checked on a device.
 */
class HistoryRowsTest {

    private fun you(id: Long, text: String, deleted: Boolean = false) =
        ChatMessage(id, Author.You, text, createdAt = id, deleted = deleted)

    private fun them(id: Long, text: String, deleted: Boolean = false) =
        ChatMessage(id, Author.Assistant, text, createdAt = id, deleted = deleted)

    private fun thread(vararg messages: ChatMessage, id: String = "t", updatedAt: Long = 2_000L) = Conversation(
        id = id,
        persona = Persona.Teacher,
        model = ModelSpec.Qwen05B,
        createdAt = 1_000L,
        updatedAt = updatedAt,
        messages = messages.toList(),
    )

    /** What the history query is meant to bring back for this thread. */
    private fun Conversation.asQueried(hasUnknownAuthor: Boolean = false) = HistoryRow(
        id = id,
        persona = persona.name,
        model = model.name,
        createdAt = createdAt,
        updatedAt = updatedAt,
        firstSaid = messages.firstOrNull { it.author == Author.You && !it.deleted }?.text,
        lastShown = messages.lastOrNull { !it.deleted }?.text,
        everSaid = messages.any { it.author == Author.You },
        hasUnknownAuthor = hasUnknownAuthor,
    )

    @Test
    fun `a row shows the title and preview the whole thread would`() {
        val threads = mapOf(
            "plain" to thread(them(0, "嗨"), you(1, "你好"), them(2, "在")),
            "first line deleted" to thread(them(0, "嗨"), you(1, "收回", deleted = true), them(2, "在"), you(3, "下一句")),
            "every line of theirs deleted" to thread(them(0, "嗨"), you(1, "收回", deleted = true), them(2, "在")),
            "assistant only" to thread(them(0, "一"), them(1, "二")),
            "everything deleted" to thread(them(0, "嗨", deleted = true), you(1, "收回", deleted = true)),
            "cut lands inside a character" to thread(you(0, "一二三四五六七八九十一二三四五六七八九十一二三四五👨‍👩‍👧結尾"), them(1, "好")),
            "last message has line breaks" to thread(you(0, "問"), them(1, "第一行\n第二行\n第三行")),
            "no messages" to thread(),
        )

        for ((name, whole) in threads) {
            val entry = whole.asQueried().toEntryOrNull()!!
            assertEquals(name, whole.title, entry.title)
            assertEquals(name, whole.preview, entry.preview)
        }
        assertEquals("下一句", threads.getValue("first line deleted").asQueried().toEntryOrNull()!!.title)
        assertEquals("（訊息已刪除）", threads.getValue("every line of theirs deleted").asQueried().toEntryOrNull()!!.title)
        assertEquals("還沒說話", threads.getValue("assistant only").asQueried().toEntryOrNull()!!.title)
        assertEquals("", threads.getValue("everything deleted").asQueried().toEntryOrNull()!!.preview)
    }

    @Test
    fun `a row carries the thread's own names and time`() {
        val entry = thread(you(0, "a"), id = "names", updatedAt = 4_321L).asQueried().toEntryOrNull()!!

        assertEquals("names", entry.id)
        assertEquals(Persona.Teacher, entry.persona)
        assertEquals(ModelSpec.Qwen05B, entry.model)
        assertEquals(4_321L, entry.updatedAt)
    }

    @Test
    fun `a thread that could not be opened is not listed, and the rest are newest first`() {
        val older = thread(you(0, "a"), id = "older", updatedAt = 1L)
        val newer = thread(you(0, "b"), id = "newer", updatedAt = 9L)
        val broken = thread(you(0, "c"), id = "broken", updatedAt = 5L)

        assertNull(broken.asQueried(hasUnknownAuthor = true).toEntryOrNull())
        assertEquals(
            listOf("newer", "older"),
            listOf(older.asQueried(), broken.asQueried(hasUnknownAuthor = true), newer.asQueried()).toHistory().map { it.id },
        )
    }
}
