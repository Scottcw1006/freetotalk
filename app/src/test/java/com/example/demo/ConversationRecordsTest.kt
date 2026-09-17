package com.example.demo

import com.example.demo.chat.Author
import com.example.demo.chat.ChatMessage
import com.example.demo.chat.Conversation
import com.example.demo.data.ConversationReader
import com.example.demo.data.ConversationRecord
import com.example.demo.data.SaveAction
import com.example.demo.data.toRecord
import com.example.demo.data.toSaveAction
import com.example.demo.llm.ModelSpec
import com.example.demo.persona.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rules that sit between a thread and its database row, tested without a database:
 * what goes in comes back out untouched, a row that cannot be read costs only itself,
 * and a thread nobody wrote in is removed rather than stored.
 *
 * What these cannot see is the database end — whether the row itself was kept intact.
 * That half is only checked on a device.
 */
class ConversationRecordsTest {

    private fun thread(
        id: String,
        vararg messages: ChatMessage,
        updatedAt: Long = 2_000L,
    ) = Conversation(
        id = id,
        persona = Persona.Teacher,
        model = ModelSpec.Gemma1B,
        createdAt = 1_000L,
        updatedAt = updatedAt,
        messages = messages.toList(),
    )

    @Test
    fun `what is stored reads back exactly, by id and in the full list`() {
        val original = thread(
            "round-trip",
            ChatMessage(0, Author.Assistant, "第一行\n第二行\t有 tab", createdAt = 11L),
            ChatMessage(1, Author.You, "全形\u3000空白與\u00A0不換行空白", createdAt = 12L),
            // Two characters each: a backslash then n, and two backslashes.
            ChatMessage(2, Author.You, "C:\\new 和 \\\\ 兩個反斜線", createdAt = 13L),
            ChatMessage(3, Author.Assistant, "👨‍👩‍👧 🇹🇼", createdAt = 14L),
            ChatMessage(4, Author.You, "  前後有空白  ", createdAt = 15L),
            ChatMessage(5, Author.Assistant, "長".repeat(2_048), createdAt = 16L),
            ChatMessage(6, Author.Assistant, "講到一半…（已停止）", createdAt = 17L),
            ChatMessage(7, Author.Assistant, "", createdAt = 18L),
        )
        val rows = listOf(original.toRecord())

        assertEquals(original, ConversationReader.find(rows, "round-trip"))
        assertEquals(listOf(original), ConversationReader.all(rows))
    }

    @Test
    fun `a message saved while still streaming does not read back as streaming`() {
        val saved = thread(
            "mid-reply",
            ChatMessage(0, Author.You, "hi", createdAt = 1L),
            ChatMessage(1, Author.Assistant, "還在打", createdAt = 2L, streaming = true),
        )

        val read = ConversationReader.find(listOf(saved.toRecord()), "mid-reply")

        assertEquals(
            saved.copy(messages = saved.messages.map { it.copy(streaming = false) }),
            read,
        )
    }

    @Test(timeout = 5_000)
    fun `a row that cannot be read is dropped alone, and both reads agree about it`() {
        val e = thread("e", ChatMessage(0, Author.You, "e", createdAt = 1L), updatedAt = 3_000L)
        val f = thread("f", ChatMessage(0, Author.You, "f", createdAt = 1L), updatedAt = 1_000L)
        val unknownAuthor = e.toRecord().copy(
            id = "x-author",
            messages = """[{"id":0,"author":"Robot","text":"?","createdAt":1}]""",
        )
        val notJson = e.toRecord().copy(id = "x-garbage", messages = "this is not a message list")
        val rows = listOf(unknownAuthor, e.toRecord(), notJson, f.toRecord())

        val all = ConversationReader.all(rows)

        assertEquals(listOf(e, f), all)
        assertNull(ConversationReader.find(rows, "x-author"))
        assertNull(ConversationReader.find(rows, "x-garbage"))
        assertEquals(e, ConversationReader.find(rows, "e"))
        assertNull(ConversationReader.find(rows, "never-existed"))
        for (id in listOf("x-author", "x-garbage", "e", "f", "never-existed")) {
            assertEquals(id, all.firstOrNull { it.id == id }, ConversationReader.find(rows, id))
        }
    }

    @Test
    fun `the full list is newest first`() {
        val older = thread("older", ChatMessage(0, Author.You, "a", createdAt = 1L), updatedAt = 1L)
        val newer = thread("newer", ChatMessage(0, Author.You, "b", createdAt = 1L), updatedAt = 9L)

        assertEquals(
            listOf(newer, older),
            ConversationReader.all(listOf(older.toRecord(), newer.toRecord())),
        )
    }

    @Test
    fun `a thread nobody wrote in is deleted, not written`() {
        val openerOnly = thread("opener", ChatMessage(0, Author.Assistant, "嗨"))
        val assistantOnly = thread(
            "x",
            ChatMessage(0, Author.Assistant, "一"),
            ChatMessage(1, Author.Assistant, "二"),
        )
        val empty = thread("empty")
        val spoken = thread(
            "spoken",
            ChatMessage(0, Author.Assistant, "嗨"),
            ChatMessage(1, Author.You, "你好"),
        )

        assertEquals(SaveAction.Delete("opener"), openerOnly.toSaveAction())
        assertEquals(SaveAction.Delete("x"), assistantOnly.toSaveAction())
        assertEquals(SaveAction.Delete("empty"), empty.toSaveAction())
        assertEquals(SaveAction.Write(spoken.toRecord()), spoken.toSaveAction())
    }

    @Test
    fun `the row carries the thread's own names and times`() {
        val record: ConversationRecord = thread(
            "names",
            ChatMessage(0, Author.You, "a", createdAt = 1L),
        ).toRecord()

        assertEquals("names", record.id)
        assertEquals(Persona.Teacher.name, record.persona)
        assertEquals(ModelSpec.Gemma1B.name, record.model)
        assertEquals(1_000L, record.createdAt)
        assertEquals(2_000L, record.updatedAt)
    }
}
