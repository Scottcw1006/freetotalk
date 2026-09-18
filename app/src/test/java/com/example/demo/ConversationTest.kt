package com.example.demo

import com.example.demo.chat.Author
import com.example.demo.chat.ChatMessage
import com.example.demo.chat.ChatUiState
import com.example.demo.chat.Conversation
import com.example.demo.chat.EngineStatus
import com.example.demo.chat.hasSameContentAs
import com.example.demo.llm.ModelSpec
import com.example.demo.persona.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The derived values a thread exposes about itself — what the drawer calls it, what it
 * shows underneath, whether it is worth keeping, and whether the send button works.
 *
 * None of this touches Android, but all of it is what the reader sees in the history
 * drawer, so it belongs in tests rather than in a person's hands on a device.
 */
class ConversationTest {

    private var nextId = 0L

    private fun said(text: String) = ChatMessage(nextId++, Author.You, text)
    private fun replied(text: String) = ChatMessage(nextId++, Author.Assistant, text)

    private fun thread(
        vararg messages: ChatMessage,
        id: String = "thread-$nextId",
        updatedAt: Long = 1_000L,
    ) = Conversation(
        id = id,
        persona = Persona.Sibling,
        model = ModelSpec.Gemma1B,
        createdAt = 0L,
        updatedAt = updatedAt,
        messages = messages.toList(),
    )

    // ---- title ---------------------------------------------------------------

    @Test
    fun `a thread is named after the first thing the user said`() {
        val conversation = thread(replied("開場白"), said("第一句"), said("第二句"))

        assertEquals("第一句", conversation.title)
    }

    /**
     * The opener is written by the app, so every thread of the same persona would carry
     * the same name — the drawer would become five rows of "欸，你今天還好嗎？".
     */
    @Test
    fun `the assistant opener never becomes the name`() {
        val conversation = thread(replied("欸，你今天還好嗎？"), said("還行"))

        assertEquals("還行", conversation.title)
    }

    @Test
    fun `a thread with nothing from the user is called 還沒說話`() {
        assertEquals("還沒說話", thread(replied("開場白")).title)
        assertEquals("還沒說話", thread().title)
    }

    /** A newline in the drawer row would push the row to two lines and shift the list. */
    @Test
    fun `newlines in the name become spaces`() {
        val conversation = thread(said("第一行\n第二行"))

        assertEquals("第一行 第二行", conversation.title)
    }

    /**
     * A cut that happens to fit on one line gets no ellipsis from the row's
     * TextOverflow.Ellipsis, so the cut itself has to say "there is more".
     */
    @Test
    fun `a long first message is cut to 26 characters and ends with an ellipsis`() {
        val conversation = thread(said("this is a very long first sentence that keeps going"))

        assertEquals("this is a very long first …", conversation.title)
        assertEquals("字".repeat(26) + "…", thread(said("字".repeat(40))).title)
    }

    @Test
    fun `a first message of exactly 26 characters is not marked as cut`() {
        assertEquals("字".repeat(26), thread(said("字".repeat(26))).title)
    }

    // ---- preview -------------------------------------------------------------

    /** The preview answers "where did this thread get to", so it follows the last line. */
    @Test
    fun `the preview is the last message whoever said it`() {
        assertEquals("助理最後說的", thread(said("我說的"), replied("助理最後說的")).preview)
        assertEquals("我最後說的", thread(replied("助理說的"), said("我最後說的")).preview)
    }

    @Test
    fun `newlines in the preview become spaces`() {
        assertEquals("上 下", thread(said("上\n下")).preview)
    }

    @Test
    fun `a long last message is cut to 40 characters and ends with an ellipsis`() {
        assertEquals("字".repeat(40) + "…", thread(said("字".repeat(60))).preview)
    }

    @Test
    fun `a last message of exactly 40 characters is not marked as cut`() {
        assertEquals("字".repeat(40), thread(said("字".repeat(40))).preview)
    }

    @Test
    fun `a thread with no messages previews as nothing`() {
        assertEquals("", thread().preview)
    }

    // ---- a cut never splits a character ---------------------------------------

    /** A high surrogate with no low one after it, or a low one with no high one before it. */
    private fun String.hasLoneSurrogate(): Boolean = indices.any { i ->
        val c = this[i]
        (c.isHighSurrogate() && (i + 1 >= length || !this[i + 1].isLowSurrogate())) ||
            (c.isLowSurrogate() && (i == 0 || !this[i - 1].isHighSurrogate()))
    }

    /**
     * The cut lands between the two halves of 🌧. Taking a plain prefix of code units
     * leaves half an emoji in front of the "…" — a broken glyph in the drawer, and a
     * string uiautomator refuses to dump.
     */
    @Test
    fun `a title cut in the middle of an emoji keeps no half of it`() {
        val first = "字".repeat(25) + "🌧" + "後面還有很多字"
        val title = thread(said(first)).title

        assertFalse("lone surrogate in \"$title\"", title.hasLoneSurrogate())
        assertTrue(title.endsWith("…"))
        val kept = title.removeSuffix("…")
        assertTrue(first.startsWith(kept))
        assertTrue(kept.startsWith("字".repeat(25)))
    }

    @Test
    fun `a preview cut in the middle of an emoji keeps no half of it`() {
        val last = "字".repeat(39) + "🌧" + "後面還有很多字"
        val preview = thread(said(last)).preview

        assertFalse("lone surrogate in \"$preview\"", preview.hasLoneSurrogate())
        assertTrue(preview.endsWith("…"))
        val kept = preview.removeSuffix("…")
        assertTrue(last.startsWith(kept))
        assertTrue(kept.startsWith("字".repeat(39)))
    }

    /** Stepping back a unit whenever the last character is an emoji would cut a line that fits. */
    @Test
    fun `an emoji that exactly fills the limit is kept whole with no ellipsis`() {
        val title = "字".repeat(24) + "🌧"
        val preview = "字".repeat(38) + "🌧"

        assertEquals(title, thread(said(title)).title)
        assertEquals(preview, thread(said(preview)).preview)
    }

    /** What the emoji fix must leave alone in the same stretch of code. */
    @Test
    fun `guarding emoji leaves the rest of the title and preview rules as they were`() {
        assertTrue(thread(said("a".repeat(60))).title.endsWith("…"))
        assertTrue(thread(said("a".repeat(60))).preview.endsWith("…"))
        assertEquals("a".repeat(26), thread(said("a".repeat(26))).title)
        assertEquals("a".repeat(40), thread(said("a".repeat(40))).preview)

        assertEquals("上 下", thread(said("上\n下")).title)
        assertEquals("上 下", thread(said("上\n下")).preview)
        // The drawer does not borrow search's whitespace folding.
        assertEquals("北京　天氣", thread(said("北京　天氣")).title)
        assertEquals("北京　天氣", thread(said("北京　天氣")).preview)

        assertEquals("還沒說話", thread(replied("開場白")).title)
    }

    // ---- a character is what the reader sees, not a code point ----------------

    // Spelled out code unit by code unit so the expectations below never lean on the
    // same character segmentation the code under test uses.
    private val family = "👨‍👩‍👧" // 👨‍👩‍👧, 8 units
    private val flag = "🇹🇼" // 🇹🇼, 4 units
    private val thumbs = "👍🏽" // 👍🏽, 4 units
    private val accent = "́"

    /**
     * UAX #29 glues each of these together by a different rule. Every shape puts the cut
     * after the character's first code point — a spot where no surrogate pair is split —
     * so guarding pairs alone lets 👨, 🇹 or a toneless 👍 through.
     */
    @Test
    fun `a title cut inside a combined emoji keeps all of it or none of it`() {
        val lead = "字".repeat(24)
        for (character in listOf(family, flag, thumbs)) {
            val title = thread(said(lead + character + "後面還有字")).title
            assertTrue("\"$title\"", title == "$lead…" || title == "$lead$character…")
        }
    }

    @Test
    fun `a preview cut inside a combined emoji keeps all of it or none of it`() {
        val lead = "字".repeat(38)
        for (character in listOf(family, flag, thumbs)) {
            val preview = thread(said(lead + character + "後面還有字")).preview
            assertTrue("\"$preview\"", preview == "$lead…" || preview == "$lead$character…")
        }
    }

    /** Stepping back out of one character must stop at its start, not swallow the one before. */
    @Test
    fun `stepping out of a combined emoji keeps the character before it`() {
        val lead = "字".repeat(21)
        val title = thread(said(lead + thumbs + family + "後面還有字")).title
        assertTrue("\"$title\"", title == "$lead$thumbs…" || title == "$lead$thumbs$family…")
    }

    @Test
    fun `a combined emoji that exactly fills the limit is kept whole with no ellipsis`() {
        assertEquals("字".repeat(18) + family, thread(said("字".repeat(18) + family)).title)
        assertEquals("字".repeat(32) + family, thread(said("字".repeat(32) + family)).preview)
    }

    /** Always stepping back would cut this down to nothing but the "…". */
    @Test
    fun `a single character longer than the limit is not cut down to a bare ellipsis`() {
        val longForTitle = "e" + accent.repeat(40)
        val title = thread(said(longForTitle + "尾巴")).title
        assertTrue("\"$title\"", title.startsWith(longForTitle))

        val longForPreview = "e" + accent.repeat(60)
        val preview = thread(said(longForPreview + "尾巴")).preview
        assertTrue("\"$preview\"", preview.startsWith(longForPreview))
    }

    // ---- isBlank -------------------------------------------------------------

    /**
     * Every thread opens with something from the assistant, so counting messages would
     * make every fresh thread look used and the drawer would fill with them.
     */
    @Test
    fun `a thread holding only the opener is still blank`() {
        assertTrue(thread(replied("欸，你今天還好嗎？")).isBlank)
        assertTrue(thread().isBlank)
    }

    @Test
    fun `one message from the user is enough to stop being blank`() {
        assertFalse(thread(replied("開場白"), said("嗨")).isBlank)
    }

    // ---- canSend -------------------------------------------------------------

    @Test
    fun `sending needs a ready engine`() {
        val conversation = thread(said("嗨"))

        assertTrue(ChatUiState(conversation, status = EngineStatus.Ready).canSend)
        assertFalse(ChatUiState(conversation, status = EngineStatus.Extracting(0.5f)).canSend)
        assertFalse(ChatUiState(conversation, status = EngineStatus.Starting).canSend)
        assertFalse(ChatUiState(conversation, status = EngineStatus.Failed("壞了")).canSend)
    }

    /** Two questions in flight at once would interleave two replies into one bubble. */
    @Test
    fun `sending is blocked while a reply is still arriving`() {
        val state = ChatUiState(
            thread(said("嗨")),
            status = EngineStatus.Ready,
            isReplying = true,
        )

        assertFalse(state.canSend)
    }

    // ---- hasSameContentAs ----------------------------------------------------

    /**
     * "Has anything changed" decides whether a thread's last-updated time moves. A reply
     * that has stopped arriving holds exactly the text it already held, so the streaming
     * flag must not count as a change — otherwise pressing stop would look like an edit.
     */
    @Test
    fun `the streaming flag is not a change`() {
        val streaming = thread(ChatMessage(1L, Author.Assistant, "半句話", streaming = true))
        val settled = thread(ChatMessage(1L, Author.Assistant, "半句話", streaming = false))

        assertTrue(streaming.hasSameContentAs(settled))
    }

    @Test
    fun `changing the words is a change`() {
        val before = thread(ChatMessage(1L, Author.Assistant, "原本"))
        val after = thread(ChatMessage(1L, Author.Assistant, "改過"))

        assertFalse(before.hasSameContentAs(after))
    }

    @Test
    fun `adding or removing a message is a change`() {
        val one = thread(ChatMessage(1L, Author.You, "嗨"))
        val two = thread(
            ChatMessage(1L, Author.You, "嗨"),
            ChatMessage(2L, Author.Assistant, "嗨嗨"),
        )

        assertFalse(one.hasSameContentAs(two))
        assertFalse(two.hasSameContentAs(one))
    }

    /** Same words, different messages — replacing a message must not read as unchanged. */
    @Test
    fun `a replaced message is a change even when the text survives`() {
        val before = thread(ChatMessage(1L, Author.You, "嗨"))
        val after = thread(ChatMessage(7L, Author.You, "嗨"))

        assertFalse(before.hasSameContentAs(after))
    }
}
