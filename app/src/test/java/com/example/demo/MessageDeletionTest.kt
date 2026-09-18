package com.example.demo

import com.example.demo.chat.Author
import com.example.demo.chat.ChatMessage
import com.example.demo.chat.Conversation
import com.example.demo.chat.MessageAction
import com.example.demo.chat.clipboardText
import com.example.demo.chat.contextForNextTurn
import com.example.demo.chat.hasSameContentAs
import com.example.demo.chat.longPressActions
import com.example.demo.chat.searchConversations
import com.example.demo.chat.withMessageDeleted
import com.example.demo.data.ConversationReader
import com.example.demo.data.LegacyThreadRow
import com.example.demo.data.SaveAction
import com.example.demo.data.toRecord
import com.example.demo.data.toSaveAction
import com.example.demo.data.toThreadRecordOrNull
import com.example.demo.llm.ModelSpec
import com.example.demo.llm.Turn
import com.example.demo.persona.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A deleted message stays in the thread — its place, its words, the way back — and is
 * treated as absent by everything that reads the thread for another purpose: the title,
 * the preview, search, and what the model is shown. The one reader that must still count
 * it is "is this thread blank", because a blank thread is removed from the phone.
 */
class MessageDeletionTest {

    private fun you(id: Long, text: String, deleted: Boolean = false) =
        ChatMessage(id, Author.You, text, createdAt = id, deleted = deleted)

    private fun them(id: Long, text: String, deleted: Boolean = false, streaming: Boolean = false) =
        ChatMessage(id, Author.Assistant, text, createdAt = id, streaming = streaming, deleted = deleted)

    private fun thread(vararg messages: ChatMessage, id: String = "t") = Conversation(
        id = id,
        persona = Persona.Teacher,
        model = ModelSpec.Qwen05B,
        createdAt = 1_000L,
        updatedAt = 2_000L,
        messages = messages.toList(),
    )

    private fun Conversation.deleting(id: Long) = withMessageDeleted(id, deleted = true)
    private fun Conversation.restoring(id: Long) = withMessageDeleted(id, deleted = false)

    // ---- deleting and restoring ------------------------------------------------

    @Test
    fun `deleting marks only that message and keeps its words and its place`() {
        val original = thread(them(0, "嗨"), you(1, "first"), them(2, "講到一半…（已停止）"))

        val deleted = original.deleting(2)

        assertEquals(
            listOf(them(0, "嗨"), you(1, "first"), them(2, "講到一半…（已停止）", deleted = true)),
            deleted.messages,
        )
        assertEquals(original, deleted.restoring(2))
    }

    // ---- title ---------------------------------------------------------------

    @Test
    fun `the title is the first thing the user said that is not deleted`() {
        val original = thread(them(0, "嗨"), you(1, "first"), them(2, "ok"), you(3, "second"))

        assertEquals("second", original.deleting(1).title)
        assertEquals("first", original.deleting(1).restoring(1).title)
    }

    @Test
    fun `a title taken from a later message is still cut short`() {
        val long = "b".repeat(40)
        val conversation = thread(them(0, "嗨"), you(1, "first", deleted = true), you(2, long))

        assertEquals("b".repeat(26) + "…", conversation.title)
    }

    @Test
    fun `a thread whose every user message is deleted says so, and is not called unspoken`() {
        val allGone = thread(them(0, "嗨"), you(1, "one", deleted = true), them(2, "reply"), you(3, "two", deleted = true))

        assertEquals("（訊息已刪除）", allGone.title)
        assertEquals("two", allGone.restoring(3).title)
        assertEquals("還沒說話", thread(them(0, "嗨")).title)
    }

    // ---- preview -------------------------------------------------------------

    @Test
    fun `the preview is the last message that is not deleted, whoever wrote it`() {
        val original = thread(them(0, "嗨"), you(1, "question"), them(2, "answer"))

        val answerGone = original.deleting(2)
        assertEquals("question", answerGone.preview)

        val allGone = answerGone.deleting(1).deleting(0)
        assertEquals("", allGone.preview)

        assertEquals("answer", allGone.restoring(2).preview)
    }

    // ---- blank ---------------------------------------------------------------

    @Test
    fun `deleting everything the user said does not make the thread blank`() {
        val allGone = thread(them(0, "嗨"), you(1, "one", deleted = true), them(2, "reply"))

        assertFalse(allGone.isBlank)
        assertEquals(SaveAction.Write(allGone.toRecord()), allGone.toSaveAction())
    }

    @Test
    fun `a thread nobody wrote in is blank whether or not its opener is deleted`() {
        val openerOnly = thread(them(0, "嗨"), id = "opener")
        val deletedOpener = thread(them(0, "嗨", deleted = true), id = "deleted-opener")

        assertTrue(openerOnly.isBlank)
        assertTrue(deletedOpener.isBlank)
        assertEquals(SaveAction.Delete("opener"), openerOnly.toSaveAction())
        assertEquals(SaveAction.Delete("deleted-opener"), deletedOpener.toSaveAction())
    }

    // ---- search --------------------------------------------------------------

    @Test
    fun `a deleted message cannot be found, and the rest of its thread still can`() {
        val original = thread(them(0, "嗨"), you(1, "apple kiwi"), them(2, "banana kiwi"))
        val deleted = original.deleting(1)

        assertTrue(searchConversations("apple", listOf(deleted)).isEmpty())
        val kiwi = searchConversations("kiwi", listOf(deleted))
        assertEquals(1, kiwi.size)
        assertEquals(1, kiwi.single().matchedMessageCount)
        assertEquals(listOf(2L), kiwi.single().hits.map { it.message.id })
        assertTrue(searchConversations("已刪除", listOf(deleted)).isEmpty())
        assertTrue(searchConversations("復原", listOf(deleted)).isEmpty())

        val restored = deleted.restoring(1)
        assertEquals(listOf(1L), searchConversations("apple", listOf(restored)).single().hits.map { it.message.id })
        assertEquals(2, searchConversations("kiwi", listOf(restored)).single().matchedMessageCount)
    }

    @Test
    fun `a deleted message is not counted among the hits left out`() {
        val conversation = thread(
            you(0, "kiwi 1"), them(1, "kiwi 2"), you(2, "kiwi 3", deleted = true), them(3, "kiwi 4"),
        )

        val result = searchConversations("kiwi", listOf(conversation)).single()

        assertEquals(3, result.hits.size)
        assertEquals(0, result.matchedMessageCount - result.hits.size)
    }

    // ---- model context -------------------------------------------------------

    private val u1 = you(1, "U1")
    private val a1 = them(2, "A1")
    private val u2 = you(3, "U2")
    private val a2 = them(4, "A2")
    private val spoken = thread(them(0, "開場白"), u1, a1, u2, a2)

    @Test
    fun `with nothing deleted the context is everything said, minus the leading assistant turns`() {
        assertEquals(
            listOf(Turn(true, "U1"), Turn(false, "A1"), Turn(true, "U2"), Turn(false, "A2")),
            spoken.contextForNextTurn(),
        )
    }

    @Test
    fun `deleted messages leave the context before leading assistant turns are skipped`() {
        assertEquals(
            listOf(Turn(true, "U2"), Turn(false, "A2")),
            spoken.deleting(1).contextForNextTurn(),
        )
    }

    @Test
    fun `a restored message is back in the context`() {
        assertEquals(
            listOf(Turn(true, "U1"), Turn(false, "A1"), Turn(true, "U2"), Turn(false, "A2")),
            spoken.deleting(1).restoring(1).contextForNextTurn(),
        )
    }

    @Test
    fun `a thread with everything deleted has an empty context`() {
        val allGone = spoken.messages.fold(spoken) { thread, message -> thread.deleting(message.id) }

        assertEquals(emptyList<Turn>(), allGone.contextForNextTurn())
    }

    // ---- has the content changed ----------------------------------------------

    @Test
    fun `deleting or restoring a message is a change of content`() {
        assertFalse(spoken.hasSameContentAs(spoken.deleting(1)))
        assertFalse(spoken.deleting(1).hasSameContentAs(spoken))
    }

    @Test
    fun `a reply that merely stopped streaming is still not a change of content`() {
        val streaming = thread(you(0, "hi"), them(1, "還在打", streaming = true))
        val settled = thread(you(0, "hi"), them(1, "還在打"))

        assertTrue(streaming.hasSameContentAs(settled))
    }

    // ---- what a long press offers ----------------------------------------------

    private val copyAndDelete = setOf(MessageAction.Copy, MessageAction.Delete)

    @Test
    fun `every finished message can be copied and deleted`() {
        val finished = listOf(
            you(1, "我說的"),
            them(2, "對方說的"),
            them(0, "開場白"),
            them(3, "講到一半…（已停止）"),
            them(4, "出錯了：out of memory"),
        )

        for (message in finished) assertEquals(message.text, copyAndDelete, message.longPressActions)
    }

    @Test
    fun `a message still streaming offers nothing, with or without text`() {
        assertEquals(emptySet<MessageAction>(), them(1, "", streaming = true).longPressActions)
        assertEquals(emptySet<MessageAction>(), them(1, "寫到一半", streaming = true).longPressActions)
    }

    @Test
    fun `a deleted message offers nothing`() {
        assertEquals(emptySet<MessageAction>(), you(1, "gone", deleted = true).longPressActions)
    }

    @Test
    fun `finished messages stay available while another one is streaming`() {
        val conversation = thread(them(0, "嗨"), you(1, "hi"), them(2, "…", streaming = true))

        assertEquals(copyAndDelete, conversation.messages[0].longPressActions)
        assertEquals(copyAndDelete, conversation.messages[1].longPressActions)
        assertEquals(emptySet<MessageAction>(), conversation.messages[2].longPressActions)
    }

    // ---- what copy puts on the clipboard ---------------------------------------

    @Test
    fun `copying takes the words as stored, not as drawn`() {
        val markdown = "**重點**：\n- 蘋果\n\n1) 香蕉"
        val typed = "a*b*  **x**"
        val stopped = "講到一半…（已停止）"

        assertEquals(markdown, them(1, markdown).clipboardText)
        assertEquals(typed, you(2, typed).clipboardText)
        assertEquals(stopped, them(3, stopped).clipboardText)
        assertTrue(them(3, stopped).clipboardText.endsWith("…（已停止）"))
    }

    // ---- stored and read back --------------------------------------------------

    @Test
    fun `deleted messages read back deleted, with their words, by id and in the full list`() {
        val original = thread(
            them(0, "嗨"),
            you(1, "第一行\n第二行", deleted = true),
            them(2, "講到\n一半…（已停止）", deleted = true),
            you(3, "還在"),
            id = "round-trip",
        )
        val rows = listOf(original.toRecord())

        assertEquals(original, ConversationReader.find(rows, "round-trip"))
        assertEquals(listOf(original), ConversationReader.all(rows))
    }

    /** The row is written out by hand in the shape the app stored before messages could be deleted. */
    @Test
    fun `a thread stored before messages could be deleted reads back with nothing deleted`() {
        val row = LegacyThreadRow(
            id = "old",
            persona = Persona.Teacher.name,
            model = ModelSpec.Qwen05B.name,
            createdAt = 1_000L,
            updatedAt = 2_000L,
            messages = """[{"id":0,"author":"Assistant","text":"嗨","createdAt":0},""" +
                """{"id":1,"author":"You","text":"第一行\n第二行","createdAt":1},""" +
                """{"id":2,"author":"Assistant","text":"講到一半…（已停止）","createdAt":2}]""",
        )
        val expected = thread(them(0, "嗨"), you(1, "第一行\n第二行"), them(2, "講到一半…（已停止）"), id = "old")

        val carriedOver = listOfNotNull(row.toThreadRecordOrNull())

        assertEquals(expected, ConversationReader.find(carriedOver, "old"))
        assertEquals(listOf(expected), ConversationReader.all(carriedOver))
    }
}
