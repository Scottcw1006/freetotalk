package com.example.demo

import com.example.demo.data.ConversationRecord
import com.example.demo.data.LegacyThreadRow
import com.example.demo.data.MessageRecord
import com.example.demo.data.ThreadRecord
import com.example.demo.data.toThreadRecordOrNull
import com.example.demo.llm.ModelSpec
import com.example.demo.persona.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the upgrade makes of a row from before messages had rows of their own. Every row
 * here is written out by hand in the shape it was stored in back then — never produced by
 * today's code, which could only ever agree with itself.
 *
 * What these cannot see is the database end: whether the migration reads every old row,
 * writes what this hands it, and leaves nothing of the old shape behind. That half is
 * only checked on a device.
 */
class LegacyThreadRowsTest {

    // Neither is the default: a conversion that drops the field and falls back to the
    // default would otherwise come out equal and pass.
    private val persona = Persona.Teacher.name
    private val model = ModelSpec.Qwen05B.name

    private fun row(id: String, messages: String, updatedAt: Long = 2_000L) =
        LegacyThreadRow(id, persona, model, createdAt = 1_000L, updatedAt = updatedAt, messages = messages)

    private fun expected(id: String, updatedAt: Long = 2_000L, vararg messages: MessageRecord) = ThreadRecord(
        ConversationRecord(id, persona, model, createdAt = 1_000L, updatedAt = updatedAt),
        messages.toList(),
    )

    private fun message(
        thread: String,
        id: Long,
        position: Int,
        author: String,
        text: String,
        createdAt: Long,
        deleted: Boolean = false,
    ) = MessageRecord(thread, id, position, author, text, createdAt, deleted)

    @Test
    fun `a row from before messages could be deleted comes over with nothing deleted`() {
        // Ids with a gap: a reply stopped before it said anything is removed, not renumbered.
        val old = row(
            "plain",
            """[{"id":0,"author":"Assistant","text":"嗨","createdAt":10},""" +
                """{"id":1,"author":"You","text":"你好","createdAt":11},""" +
                """{"id":2,"author":"Assistant","text":"在","createdAt":12},""" +
                """{"id":4,"author":"You","text":"再說","createdAt":14}]""",
            updatedAt = 7_777L,
        )

        assertEquals(
            expected(
                "plain",
                7_777L,
                message("plain", 0, 0, "Assistant", "嗨", 10),
                message("plain", 1, 1, "You", "你好", 11),
                message("plain", 2, 2, "Assistant", "在", 12),
                message("plain", 4, 3, "You", "再說", 14),
            ),
            old.toThreadRecordOrNull(),
        )
    }

    @Test
    fun `deleted messages come over deleted, one by one, with their words`() {
        val old = row(
            "deleted",
            """[{"id":0,"author":"Assistant","text":"嗨","createdAt":10},""" +
                """{"id":1,"author":"You","text":"收回這句","createdAt":11,"deleted":true},""" +
                """{"id":2,"author":"Assistant","text":"講到一半…（已停止）","createdAt":12,"deleted":true},""" +
                """{"id":3,"author":"You","text":"還在","createdAt":13}]""",
        )

        assertEquals(
            expected(
                "deleted",
                2_000L,
                message("deleted", 0, 0, "Assistant", "嗨", 10),
                message("deleted", 1, 1, "You", "收回這句", 11, deleted = true),
                message("deleted", 2, 2, "Assistant", "講到一半…（已停止）", 12, deleted = true),
                message("deleted", 3, 3, "You", "還在", 13),
            ),
            old.toThreadRecordOrNull(),
        )
    }

    @Test
    fun `text comes over code point for code point`() {
        val long = "長".repeat(2_048)
        // As the JSON text held them: \n and \t are escapes for a line break and a tab,
        // \\n is a backslash followed by the letter n, \\\\ is two backslashes.
        val old = row(
            "text",
            """[{"id":0,"author":"Assistant","text":"第一行\n第二行\t有 tab","createdAt":1},""" +
                """{"id":1,"author":"You","text":"全形${'\u3000'}空白與${'\u00A0'}不換行空白","createdAt":2},""" +
                """{"id":2,"author":"You","text":"C:\\new 和 \\\\ 兩個反斜線","createdAt":3},""" +
                """{"id":3,"author":"Assistant","text":"👨‍👩‍👧 🇹🇼","createdAt":4},""" +
                """{"id":4,"author":"You","text":"  前後有空白  ","createdAt":5},""" +
                """{"id":5,"author":"Assistant","text":"$long","createdAt":6},""" +
                """{"id":6,"author":"Assistant","text":"講到一半…（已停止）","createdAt":7},""" +
                """{"id":7,"author":"Assistant","text":"","createdAt":8}]""",
        )

        assertEquals(
            expected(
                "text",
                2_000L,
                message("text", 0, 0, "Assistant", "第一行\n第二行\t有 tab", 1),
                message("text", 1, 1, "You", "全形\u3000空白與\u00A0不換行空白", 2),
                message("text", 2, 2, "You", "C:\\new 和 \\\\ 兩個反斜線", 3),
                message("text", 3, 3, "Assistant", "👨‍👩‍👧 🇹🇼", 4),
                message("text", 4, 4, "You", "  前後有空白  ", 5),
                message("text", 5, 5, "Assistant", long, 6),
                message("text", 6, 6, "Assistant", "講到一半…（已停止）", 7),
                message("text", 7, 7, "Assistant", "", 8),
            ),
            old.toThreadRecordOrNull(),
        )
    }

    @Test(timeout = 5_000)
    fun `a row that cannot be made sense of is left behind alone`() {
        val e = row("e", """[{"id":0,"author":"You","text":"e","createdAt":1}]""")
        val f = row("f", """[{"id":0,"author":"You","text":"f","createdAt":1}]""")
        val notJson = row("x-garbage", "this is not a message list")
        val unknownAuthor = row("x-author", """[{"id":0,"author":"Robot","text":"?","createdAt":1}]""")
        val noText = row("x-no-text", """[{"id":0,"author":"You","createdAt":1}]""")
        val sameIdTwice = row(
            "x-same-id",
            """[{"id":0,"author":"You","text":"一","createdAt":1},""" +
                """{"id":0,"author":"Assistant","text":"二","createdAt":2}]""",
        )

        val converted = listOf(notJson, e, unknownAuthor, noText, f, sameIdTwice)
            .associate { it.id to it.toThreadRecordOrNull() }

        assertEquals(expected("e", 2_000L, message("e", 0, 0, "You", "e", 1)), converted["e"])
        assertEquals(expected("f", 2_000L, message("f", 0, 0, "You", "f", 1)), converted["f"])
        assertNull(converted["x-garbage"])
        assertNull(converted["x-author"])
        assertNull(converted["x-no-text"])
        assertNull(converted["x-same-id"])
        assertEquals(listOf("e", "f"), converted.filterValues { it != null }.keys.toList())
    }

    /**
     * Clearing out threads nobody wrote in is what happens when one is opened and left,
     * not something the upgrade does on the side.
     */
    @Test
    fun `rows the app could not have written, but that make sense, come over as they are`() {
        val assistantOnly = row(
            "assistant-only",
            """[{"id":0,"author":"Assistant","text":"一","createdAt":1},""" +
                """{"id":1,"author":"Assistant","text":"二","createdAt":2}]""",
        )
        val noMessages = row("no-messages", "[]")

        assertEquals(
            expected(
                "assistant-only",
                2_000L,
                message("assistant-only", 0, 0, "Assistant", "一", 1),
                message("assistant-only", 1, 1, "Assistant", "二", 2),
            ),
            assistantOnly.toThreadRecordOrNull(),
        )
        assertEquals(expected("no-messages", 2_000L), noMessages.toThreadRecordOrNull())
    }
}
