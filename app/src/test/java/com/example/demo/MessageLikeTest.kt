package com.example.demo

import com.example.demo.chat.Author
import com.example.demo.chat.ChatMessage
import com.example.demo.chat.Conversation
import com.example.demo.chat.ConversationHits
import com.example.demo.chat.MessageAction
import com.example.demo.chat.clipboardText
import com.example.demo.chat.contextForNextTurn
import com.example.demo.chat.hasSameContentAs
import com.example.demo.chat.longPressActions
import com.example.demo.chat.searchConversations
import com.example.demo.chat.withMessageDeleted
import com.example.demo.chat.withMessageLiked
import com.example.demo.data.ConversationReader
import com.example.demo.data.SaveAction
import com.example.demo.data.toRecord
import com.example.demo.data.toSaveAction
import com.example.demo.llm.ModelSpec
import com.example.demo.persona.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A like is a mark on one of the other side's finished messages and nothing more: it
 * comes and goes from the long-press menu, survives a delete and a restore, is saved and
 * read back, and changes no answer anything else gives about the thread.
 */
class MessageLikeTest {

    private fun you(id: Long, text: String, liked: Boolean = false, deleted: Boolean = false) =
        ChatMessage(id, Author.You, text, createdAt = id, liked = liked, deleted = deleted)

    private fun them(
        id: Long,
        text: String,
        liked: Boolean = false,
        deleted: Boolean = false,
        streaming: Boolean = false,
    ) = ChatMessage(id, Author.Assistant, text, createdAt = id, streaming = streaming, deleted = deleted, liked = liked)

    private fun thread(vararg messages: ChatMessage, id: String = "t") = Conversation(
        id = id,
        persona = Persona.Teacher,
        model = ModelSpec.Qwen05B,
        createdAt = 1_000L,
        updatedAt = 2_000L,
        messages = messages.toList(),
    )

    private fun Conversation.liking(id: Long) = withMessageLiked(id, liked = true)
    private fun Conversation.unliking(id: Long) = withMessageLiked(id, liked = false)
    private fun Conversation.likedIds() = messages.filter { it.liked }.map { it.id }

    // ---- what a long press offers (§1.1–§1.5) ----------------------------------

    private val toLike = setOf(MessageAction.Copy, MessageAction.Like, MessageAction.Delete)
    private val toUnlike = setOf(MessageAction.Copy, MessageAction.Unlike, MessageAction.Delete)
    private val copyAndDelete = setOf(MessageAction.Copy, MessageAction.Delete)

    @Test
    fun `every finished message from the other side can be liked, and a liked one unliked`() {
        val kinds = listOf(
            them(2, "對方正常的回覆"),
            them(0, "開場白"),
            them(3, "講到一半…（已停止）"),
            them(4, "出錯了：out of memory"),
            them(5, "（這次沒有產生回覆，再說一次試試）"),
        )

        for (message in kinds) {
            assertEquals(message.text, toLike, message.longPressActions)
            assertEquals(message.text, toUnlike, message.copy(liked = true).longPressActions)
        }
    }

    @Test
    fun `what the user said is never offered a like, even when marked liked from outside`() {
        assertEquals(copyAndDelete, you(1, "我說的").longPressActions)
        assertEquals(copyAndDelete, you(1, "我說的", liked = true).longPressActions)
    }

    @Test
    fun `a message streaming in or deleted offers nothing, liked or not`() {
        assertEquals(emptySet<MessageAction>(), them(1, "", streaming = true).longPressActions)
        assertEquals(emptySet<MessageAction>(), them(1, "寫到一半", streaming = true).longPressActions)
        assertEquals(emptySet<MessageAction>(), them(1, "gone", deleted = true).longPressActions)
        assertEquals(emptySet<MessageAction>(), them(1, "gone", liked = true, deleted = true).longPressActions)
    }

    @Test
    fun `finished replies can still be liked while another one is streaming`() {
        val conversation = thread(them(0, "嗨"), you(1, "hi"), them(2, "好", liked = true), them(3, "…", streaming = true))

        assertEquals(toLike, conversation.messages[0].longPressActions)
        assertEquals(toUnlike, conversation.messages[2].longPressActions)
        assertEquals(emptySet<MessageAction>(), conversation.messages[3].longPressActions)
    }

    // ---- liking and unliking (§2.3, §3.4) --------------------------------------

    @Test
    fun `each like is its own, and liking changes nothing else`() {
        val original = thread(them(0, "一"), you(1, "q"), them(2, "二"), them(3, "三"))

        val second = original.liking(2)
        assertEquals(listOf(2L), second.likedIds())
        assertEquals(original.messages, second.messages.map { it.copy(liked = false) })

        assertEquals(listOf(0L), second.liking(0).unliking(2).likedIds())
        assertEquals(listOf(3L), original.liking(3).unliking(3).liking(3).likedIds())
        // Taken back, a like leaves nothing behind.
        assertEquals(original, original.liking(2).unliking(2))
    }

    @Test
    fun `a delete and a restore leave the like as it was`() {
        val original = thread(them(0, "一", liked = true), you(1, "q"), them(2, "二"))

        val roundTrip = original
            .withMessageDeleted(0, deleted = true).withMessageDeleted(0, deleted = false)
            .withMessageDeleted(2, deleted = true).withMessageDeleted(2, deleted = false)

        assertEquals(original, roundTrip)
    }

    // ---- nothing else is touched (§4.1–§4.5) -----------------------------------

    private val plain = thread(
        them(0, "來，今天想弄懂什麼？"),
        you(1, "教我分數"),
        them(2, "分數就是把一個東西切開"),
        you(3, "那小數呢"),
        them(4, "小數 zqlike 也是一種寫法"),
        them(5, "這則被刪掉了", deleted = true),
        them(6, "最後一則回覆"),
    )
    private val liked = plain.liking(0).liking(4).liking(5).liking(6)

    /** What the search screen shows, without the messages' own like state riding along. */
    private fun List<ConversationHits>.shown() = map { group ->
        Triple(
            group.conversation.id,
            group.matchedMessageCount,
            group.hits.map { Triple(it.message.id, it.snippet, it.highlights) },
        )
    }

    @Test
    fun `a like changes no answer about the thread`() {
        assertEquals(listOf(0L, 4L, 5L, 6L), liked.likedIds())

        assertEquals(plain.contextForNextTurn(), liked.contextForNextTurn())
        assertEquals(plain.title, liked.title)
        assertEquals(plain.preview, liked.preview)
        assertEquals(plain.isBlank, liked.isBlank)
        assertEquals(plain.messages.map { it.clipboardText }, liked.messages.map { it.clipboardText })

        val found = searchConversations("zqlike", listOf(plain)).shown()
        assertEquals(1, found.single().second)
        assertEquals(found, searchConversations("zqlike", listOf(liked)).shown())
        assertTrue(searchConversations("已讚", listOf(plain)).isEmpty())
        assertTrue(searchConversations("已讚", listOf(liked)).isEmpty())
    }

    @Test
    fun `a liked opener does not make a blank thread worth keeping`() {
        val blank = thread(them(0, "來，今天想弄懂什麼？", liked = true))

        assertTrue(blank.isBlank)
        assertEquals(SaveAction.Delete("t"), blank.toSaveAction())
        assertFalse(liked.unliking(0).unliking(4).unliking(6).isBlank)
    }

    // ---- the clock (§5.1) ------------------------------------------------------

    @Test
    fun `liking is not a change of content, deleting still is`() {
        assertTrue(plain.liking(4).hasSameContentAs(plain))
        assertTrue(liked.unliking(6).hasSameContentAs(liked))
        assertFalse(plain.withMessageDeleted(4, deleted = true).hasSameContentAs(plain))
    }

    // ---- saved and read back (§6.2, §6.4) --------------------------------------

    @Test
    fun `likes come back from the store as they went in`() {
        val original = thread(
            them(0, "讚了的", liked = true),
            you(1, "我說的"),
            them(2, "讚了又刪掉的", liked = true, deleted = true),
            them(3, "沒讚的"),
        )
        val records = listOf(original.toRecord())

        assertEquals(original, ConversationReader.find(records, "t"))
        assertEquals(listOf(original), ConversationReader.all(records))
    }
}
