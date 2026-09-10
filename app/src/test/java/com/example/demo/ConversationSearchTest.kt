package com.example.demo

import com.example.demo.chat.Author
import com.example.demo.chat.ChatMessage
import com.example.demo.chat.Conversation
import com.example.demo.chat.MessageHit
import com.example.demo.chat.SearchUiState
import com.example.demo.chat.ChatUiState
import com.example.demo.chat.flattenToLine
import com.example.demo.chat.normalizeForSearch
import com.example.demo.chat.withoutTrailingSpace
import com.example.demo.chat.withoutLeadingSpace
import com.example.demo.chat.hasSameContentAs
import com.example.demo.chat.searchConversations
import com.example.demo.llm.ModelSpec
import com.example.demo.persona.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSearchTest {

    // ---- fixtures ------------------------------------------------------------

    private var nextId = 0L

    private fun said(text: String) = ChatMessage(nextId++, Author.You, text)
    private fun replied(text: String) = ChatMessage(nextId++, Author.Assistant, text)

    private fun thread(
        vararg messages: ChatMessage,
        id: String = "thread-$nextId",
        persona: Persona = Persona.Sibling,
        updatedAt: Long = 1_000L,
    ) = Conversation(
        id = id,
        persona = persona,
        model = ModelSpec.Qwen05B,
        createdAt = 0L,
        updatedAt = updatedAt,
        messages = messages.toList(),
    )

    /** A thread as the app really builds one: the persona speaks first. */
    private fun threadWithOpener(
        persona: Persona,
        vararg rest: ChatMessage,
        id: String = "thread-$nextId",
        updatedAt: Long = 1_000L,
    ) = thread(
        ChatMessage(0, Author.Assistant, persona.opener),
        *rest,
        id = id,
        persona = persona,
        updatedAt = updatedAt,
    )

    // ---- who and what gets searched -----------------------------------------

    @Test
    fun `a message the user wrote is found`() {
        val threads = listOf(thread(said("我下禮拜要去北京出差"), replied("那邊這季節很乾，記得帶護唇膏。")))
        val hits = searchConversations("北京", threads)
        assertEquals(1, hits.size)
        assertEquals(Author.You, hits[0].hits[0].message.author)
    }

    @Test
    fun `a message the assistant wrote is found`() {
        val threads = listOf(thread(said("那邊天氣怎麼樣"), replied("北京這季節很乾，記得帶護唇膏。")))
        val hits = searchConversations("護唇膏", threads)
        assertEquals(1, hits.size)
        assertEquals(Author.Assistant, hits[0].hits[0].message.author)
    }

    @Test
    fun `matching ignores case`() {
        val threads = listOf(thread(replied("Hello world，今天想聊什麼？")))
        assertEquals(1, searchConversations("hello", threads).size)
        assertEquals(1, searchConversations("HELLO", threads).size)
    }

    @Test
    fun `matching ignores full-width and half-width`() {
        val wide = listOf(thread(said("我的座位是ＡＢＣ區")))
        assertEquals(1, searchConversations("ABC", wide).size)
        val narrow = listOf(thread(replied("Hello world")))
        assertEquals(1, searchConversations("ｈｅｌｌｏ", narrow).size)
    }

    @Test
    fun `traditional and simplified are not folded together`() {
        val threads = listOf(thread(said("我住在臺北")))
        assertTrue(searchConversations("台", threads).isEmpty())
        assertEquals(1, searchConversations("臺", threads).size)
    }

    @Test
    fun `surrounding whitespace in the query is ignored`() {
        val threads = listOf(thread(said("我下禮拜要去北京出差")))
        val bare = searchConversations("北京", threads)
        val padded = searchConversations("  北京  ", threads)
        // Both returning nothing would also be "the same"; say what they must both find.
        assertEquals(1, bare.size)
        assertEquals(bare.size, padded.size)
    }

    @Test
    fun `an empty query is not a search`() {
        val threads = listOf(thread(said("我下禮拜要去北京出差")))
        // The last two count as whitespace only under the wider Unicode rule — a plain
        // space would pass even if that rule had quietly shrunk back to `\s`, so on its
        // own it proves nothing.
        listOf("", "   ", "\n\t", "　", " ").forEach { query ->
            assertTrue(query, searchConversations(query, threads).isEmpty())
        }
    }

    @Test
    fun `a query that matches nothing returns nothing`() {
        val threads = listOf(thread(said("我下禮拜要去北京出差"), replied("記得帶護唇膏。")))
        assertTrue(searchConversations("zzzqqq", threads).isEmpty())
    }

    // ---- the normalisation contract highlights depend on ---------------------

    @Test
    fun `normalising never changes the length of the text`() {
        // Folding that changed length would shift every match position computed after it.
        val tricky = "İstanbul ＡＢＣ 北京 Hello"
        assertEquals(tricky.length, tricky.normalizeForSearch().length)
        assertEquals(1, "İ".normalizeForSearch().length)
    }

    @Test
    fun `a highlight points at the matched text even when the message had newlines`() {
        val threads = listOf(thread(replied("第一行\n這裡有北京兩個字")))
        val hit = searchConversations("北京", threads).single().hits.single()
        val highlight = hit.highlights.single()
        assertEquals("北京", hit.snippet.substring(highlight.start, highlight.endExclusive))
    }

    @Test
    fun `every match inside the window is highlighted`() {
        val threads = listOf(thread(said("北京的天氣跟北京的人一樣")))
        val hit = searchConversations("北京", threads).single().hits.single()
        assertEquals(2, hit.highlights.size)
        hit.highlights.forEach {
            assertEquals("北京", hit.snippet.substring(it.start, it.endExclusive))
        }
    }

    @Test
    fun `highlights stay inside the snippet and never overlap`() {
        val threads = listOf(thread(said("北京".repeat(30) + "尾巴")))
        val hit = searchConversations("北京", threads).single().hits.single()
        var previousEnd = 0
        hit.highlights.forEach {
            assertTrue(it.start in 0..hit.snippet.length)
            assertTrue(it.endExclusive in 0..hit.snippet.length)
            assertTrue(it.start < it.endExclusive)
            assertTrue(it.start >= previousEnd)
            previousEnd = it.endExclusive
        }
    }

    @Test
    fun `every unicode separator is collapsed`() {
        // Sweep the whole BMP rather than keep a hand-written list: a list would be a
        // second definition of the whitespace set, and the second one is what drifts.
        val missed = (0..0xFFFF).map { it.toChar() }.filter { character ->
            val separator = when (Character.getType(character)) {
                Character.SPACE_SEPARATOR.toInt(),
                Character.LINE_SEPARATOR.toInt(),
                Character.PARAGRAPH_SEPARATOR.toInt() -> true
                else -> character in '\u0009'..'\u000D'
            }
            separator && "a${character}b".flattenToLine() != "a b"
        }
        assertEquals(emptyList<Char>(), missed)
    }

    @Test
    fun `zero-width characters are not whitespace`() {
        // They carry no gap, and treating them as one would break the inclusion that
        // "is this query empty" leans on (§2.2).
        listOf('​', '‌', '‍', '﻿').forEach { character ->
            assertNotEquals("a b", "a${character}b".flattenToLine())
            assertEquals("a${character}b", "a${character}b".flattenToLine())
        }
        val family = "👨‍👩‍👧"
        assertEquals(family, family.flattenToLine())
    }

    @Test
    fun `a run of mixed whitespace becomes exactly one space`() {
        assertEquals("北京 天氣", "北京\r\n\t 　天氣".flattenToLine())
        assertEquals("北京 天氣", "　 北京 天氣\t".flattenToLine())
    }

    @Test
    fun `a message written with a full-width space is found with a plain one`() {
        val threads = listOf(thread(said("北京　天氣如何")))
        assertEquals(1, searchConversations("北京 天氣", threads).size)
    }

    @Test
    fun `a query written with a full-width space finds a plain message`() {
        val threads = listOf(thread(said("北京\n天氣")))
        assertEquals(1, searchConversations("北京　天氣", threads).size)
    }

    @Test
    fun `a run of spaces in the query matches a single space`() {
        val threads = listOf(thread(said("北京 天氣")))
        assertEquals(1, searchConversations("北京   天氣", threads).size)
    }

    @Test
    fun `a message written with a non-breaking space is found with a plain one`() {
        // The direction that actually happens: model output and pasted text carry these,
        // while the user types an ordinary space.
        val threads = listOf(thread(said("北京 天氣")))
        assertEquals(1, searchConversations("北京 天氣", threads).size)
    }

    @Test
    fun `a query of nothing but whitespace is not a search`() {
        val threads = listOf(thread(said("北京 天氣")))
        assertTrue(searchConversations("　", threads).isEmpty())
    }

    @Test
    fun `a highlight is as long as the flattened query, not what was typed`() {
        val threads = listOf(thread(said("我想去北京 天氣如何")))
        val hit = searchConversations("北京  天氣", threads).single().hits.single()
        val highlight = hit.highlights.single()
        assertEquals(5, highlight.endExclusive - highlight.start)
        assertEquals("北京 天氣", hit.snippet.substring(highlight.start, highlight.endExclusive))
    }

    @Test
    fun `a query with a space inside is one string, not two words to find separately`() {
        assertEquals(1, searchConversations("北京 天氣", listOf(thread(said("我在北京 天氣很好")))).size)
        // Two words joined by AND would find this one; a single substring must not.
        assertTrue(searchConversations("北京 天氣", listOf(thread(said("北京很大，天氣很好")))).isEmpty())
    }

    @Test
    fun `isActive agrees with flattening about what counts as empty`() {
        assertFalse(SearchUiState("　").isActive)
        assertFalse(SearchUiState(" ").isActive)
        assertFalse(SearchUiState(" \t\n").isActive)
        assertTrue(SearchUiState("a").isActive)
        // The two answers can only agree because they share a function. Sweep the BMP
        // for any character where they would part company.
        val disagreeing = (0..0xFFFF).map { it.toChar() }.filter { character ->
            SearchUiState(character.toString()).isActive &&
                character.toString().flattenToLine().isEmpty()
        }
        assertEquals(emptyList<Char>(), disagreeing)
    }

    @Test
    fun `allThreads keeps the in-memory copy when a thread arrives twice`() {
        val open = thread(said("第一句"), said("第二句"), id = "same", updatedAt = 500L)
        val stale = thread(said("第一句"), id = "same", updatedAt = 500L)
        val state = ChatUiState(conversation = open, history = listOf(stale))
        val merged = state.allThreads.filter { it.id == "same" }
        assertEquals(1, merged.size)
        assertEquals(2, merged.single().messages.size)
    }

    @Test
    fun `the in-memory copy wins even when the saved one looks newer`() {
        // The disk is never newer: that timestamp belongs to a version with less in it.
        // Unreachable from the UI — the history list already filters out the open thread —
        // so this is the only place the merge contract is held (spec §3.5).
        val first = said("北京 A")
        val second = said("北京 B")
        val open = thread(first, second, id = "same", updatedAt = 100L)
        val savedLooksNewer = thread(first, id = "same", updatedAt = 900L)
        val other = thread(said("北京 C"), id = "other", updatedAt = 500L)
        val state = ChatUiState(conversation = open, history = listOf(savedLooksNewer, other))

        val results = searchConversations("北京", state.allThreads)

        // The ordering half matters as much as which copy survived: "sort first, then
        // drop duplicates" keeps the saved one, puts `same` first, and reports one hit.
        assertEquals(listOf("other", "same"), results.map { it.conversation.id })
        assertEquals(2, results.last().matchedMessageCount)
    }

    @Test
    fun `allThreads contains the open thread even when it is blank`() {
        val blank = threadWithOpener(Persona.Sibling, id = "blank", updatedAt = 900L)
        val older = thread(said("舊的"), id = "older", updatedAt = 100L)
        val state = ChatUiState(conversation = blank, history = listOf(older))
        assertEquals(listOf("blank", "older"), state.allThreads.map { it.id })
    }

    // ---- what the query field is allowed to keep -----------------------------

    @Test
    fun `a leading space never survives an edit`() {
        assertEquals("", " ".withoutLeadingSpace())
        assertEquals("", "　".withoutLeadingSpace())
        assertEquals("北京", "   北京".withoutLeadingSpace())
        assertEquals("", "  ".withoutLeadingSpace())
    }

    @Test
    fun `a trailing space survives typing but not the caret leaving`() {
        assertEquals("北京 ", "北京 ".withoutLeadingSpace())
        assertEquals("北京", "北京 ".withoutTrailingSpace())
        assertEquals("北京 天氣", "北京 天氣".withoutTrailingSpace())
    }

    @Test
    fun `the field uses our whitespace rule, not the built-in one`() {
        // U+00A0 holds the lower bound: an implementation that only knows ASCII spaces
        // fails here. U+001C holds the upper: it is where the built-in trim overreaches.
        assertEquals("北京", " 北京".withoutLeadingSpace())
        assertEquals("北京", "北京 ".withoutTrailingSpace())
        assertEquals("北京", "北京".withoutLeadingSpace())
        assertEquals("北京", "北京".withoutTrailingSpace())

        // Where the built-in disagrees with us. Kotlin's Char.isWhitespace() is the union
        // of Java's isWhitespace and isSpaceChar, so it does strip U+00A0 — but it also
        // strips the C0 separators, which are not gaps in any text a reader would see.
        // Every claim in the comment above, asserted rather than asserted-about.
        assertEquals("北京", " 北京".trim())          // it does strip U+00A0 …
        assertEquals("北京", "北京".trim())          // … and the C0 delimiters too
        assertNotEquals("北京".trim(), "北京".withoutLeadingSpace())
    }

    @Test
    fun `removing the padding never changes what matches`() {
        val threads = listOf(thread(said("我想去北京 天氣如何")))
        val padded = searchConversations("  北京 天氣  ", threads)
        val trimmed = searchConversations("北京 天氣", threads)
        assertEquals(trimmed.size, padded.size)
        assertEquals(1, padded.size)
        assertEquals(
            trimmed.single().matchedMessageCount,
            padded.single().matchedMessageCount,
        )
    }

    // ---- when a thread counts as updated --------------------------------------

    @Test
    fun `a reply that stopped arriving has not changed`() {
        val streaming = thread(said("在嗎"), ChatMessage(9, Author.Assistant, "在的", streaming = true))
        // Only the streaming flag drops; not one character of text moves.
        val settled = streaming.copy(messages = streaming.messages.map { it.copy(streaming = false) })
        assertTrue(settled.hasSameContentAs(streaming))
    }

    @Test
    fun `new words, new messages and removals all count as a change`() {
        val before = thread(said("在嗎"), replied("在的"))
        val reworded = before.copy(
            messages = before.messages.dropLast(1) + before.messages.last().copy(text = "在的，怎麼了"),
        )
        assertFalse(reworded.hasSameContentAs(before))
        val appended = before.copy(messages = before.messages + said("想問你一件事"))
        assertFalse(appended.hasSameContentAs(before))
        val removed = before.copy(messages = before.messages.dropLast(1))
        assertFalse(removed.hasSameContentAs(before))
    }

    // ---- cropping ------------------------------------------------------------

    @Test
    fun `a hit deep inside a long message is cropped with a leading ellipsis`() {
        val text = "測".repeat(100) + "北京" + "尾".repeat(60)
        val hit = searchConversations("北京", listOf(thread(replied(text)))).single().hits.single()

        assertTrue(hit.snippet.startsWith("…"))
        assertTrue(hit.snippet.endsWith("…"))
        // One 60-character window plus the two ellipses that mark what was cut.
        assertEquals(62, hit.snippet.length)

        val highlight = hit.highlights.first()
        assertEquals("北京", hit.snippet.substring(highlight.start, highlight.endExclusive))
    }

    @Test
    fun `a short message is shown whole, with no ellipsis`() {
        val hit = searchConversations("北京", listOf(thread(said("我要去北京"))))
            .single().hits.single()
        assertEquals("我要去北京", hit.snippet)
    }

    @Test
    fun `cropping never splits a surrogate pair`() {
        // The window start has to land inside a pair for this to test anything. Fifteen
        // emoji put the hit at index 31, so the lead-in wants to start at 11 — the low
        // half of the sixth pair. A shape where the start never lands mid-pair passes
        // whether or not the protection is there.
        val text = "😀".repeat(15) + "測" + "北京" + "尾".repeat(60)
        val hit = searchConversations("北京", listOf(thread(replied(text)))).single().hits.single()

        hit.snippet.forEachIndexed { index, character ->
            if (character.isHighSurrogate()) {
                assertTrue(
                    "high surrogate at $index has no partner",
                    index + 1 <= hit.snippet.lastIndex && hit.snippet[index + 1].isLowSurrogate(),
                )
            }
            if (character.isLowSurrogate()) {
                assertTrue(
                    "low surrogate at $index has no partner",
                    index > 0 && hit.snippet[index - 1].isHighSurrogate(),
                )
            }
        }
    }

    /**
     * The other end of the window. [`cropping never splits a surrogate pair`] only ever
     * moves the start; nothing moves the end unless the sixty-th character from it is
     * the high half of a pair, which needs its own shape.
     */
    @Test
    fun `cropping never splits a surrogate pair at the window end`() {
        // Hit at index 30, lead-in starts at 10, so the window wants to end at 70 —
        // the high half of the sixteenth pair, since the emoji run starts at 39.
        val text = "測".repeat(30) + "北京" + "尾".repeat(7) + "😀".repeat(20)
        val hit = searchConversations("北京", listOf(thread(replied(text)))).single().hits.single()

        hit.snippet.forEachIndexed { index, character ->
            if (character.isHighSurrogate()) {
                assertTrue(
                    "high surrogate at $index has no partner",
                    index + 1 <= hit.snippet.lastIndex && hit.snippet[index + 1].isLowSurrogate(),
                )
            }
            if (character.isLowSurrogate()) {
                assertTrue(
                    "low surrogate at $index has no partner",
                    index > 0 && hit.snippet[index - 1].isHighSurrogate(),
                )
            }
        }
    }

    // ---- grouping and ordering -----------------------------------------------

    @Test
    fun `a conversation shows at most three hits but reports the true total`() {
        val threads = listOf(
            thread(said("北京 1"), said("北京 2"), said("北京 3"), said("北京 4"), said("北京 5")),
        )
        val group = searchConversations("北京", threads).single()
        assertEquals(3, group.hits.size)
        assertEquals(5, group.matchedMessageCount)
    }

    @Test
    fun `conversations come back newest first and hits stay in the order they were said`() {
        val older = thread(said("北京 A"), said("北京 B"), id = "older", updatedAt = 100L)
        val newer = thread(said("北京 C"), id = "newer", updatedAt = 900L)
        val results = searchConversations("北京", listOf(older, newer))
        assertEquals(listOf("newer", "older"), results.map { it.conversation.id })
        assertEquals(listOf("北京 A", "北京 B"), results[1].hits.map { it.snippet })
    }

    @Test
    fun `a conversation with no matching message is left out entirely`() {
        val threads = listOf(
            thread(said("我要去北京"), id = "hit"),
            thread(said("今天吃什麼"), id = "miss"),
        )
        val results = searchConversations("北京", threads)
        assertEquals(listOf("hit"), results.map { it.conversation.id })
        // A group that survived with nothing in it would be a header row over nothing.
        assertTrue(results.none { it.hits.isEmpty() })
    }

    // ---- the persona opener --------------------------------------------------

    @Test
    fun `the persona opener can be searched`() {
        val threads = listOf(threadWithOpener(Persona.Sibling, said("還好啦，就上班。")))
        val group = searchConversations("有事沒事", threads).single()
        val hit = group.hits.single()
        assertEquals(Author.Assistant, hit.message.author)
        assertEquals(threads[0].messages[0].id, hit.message.id)
    }

    @Test
    fun `a thread holding nothing but its opener still matches`() {
        val blank = threadWithOpener(Persona.Sibling)
        assertTrue(blank.isBlank)
        val group = searchConversations("有事沒事", listOf(blank)).single()
        assertEquals(1, group.hits.size)
        assertEquals(1, group.matchedMessageCount)
        assertTrue(searchConversations("zzzqqq", listOf(blank)).isEmpty())
    }

    @Test
    fun `a shared opener matches in every thread that has it`() {
        val threads = listOf(
            threadWithOpener(Persona.Sibling, id = "a", updatedAt = 300L),
            threadWithOpener(Persona.Sibling, id = "b", updatedAt = 200L),
            threadWithOpener(Persona.Sibling, id = "c", updatedAt = 100L),
        )
        // Anyone who "fixes" that noise by filtering openers out is changing what the
        // user asked for: every message they saw on screen is supposed to be findable.
        val results = searchConversations("有事沒事", threads)
        assertEquals(listOf("a", "b", "c"), results.map { it.conversation.id })
    }

    @Test
    fun `searching finds message text rather than the derived title`() {
        val threads = listOf(threadWithOpener(Persona.Sibling))
        assertEquals("還沒說話", threads[0].title)
        assertTrue(searchConversations("還沒說話", threads).isEmpty())
        assertNotNull(searchConversations("有事沒事", threads).singleOrNull())
    }

    // ---- the window boundary (§4.2) ------------------------------------------

    /**
     * The two branches of §4.2 meet exactly at "hit length = 60". One long-hit case
     * cannot pin that down: `>= 59` and `>= 61` would both pass it. So there is a case
     * on each side of the boundary and one sitting on it. BMP characters only, so that
     * §4.4 (never split a surrogate pair) cannot nudge the window and turn the expected
     * values into something that needs explaining.
     */
    private fun hitForRunOfLength(length: Int): MessageHit {
        val text = "甲".repeat(30) + "乙".repeat(length) + "丙".repeat(30)
        return searchConversations("乙".repeat(length), listOf(thread(replied(text))))
            .single().hits.single()
    }

    @Test
    fun `a hit shorter than the window keeps its lead-in`() {
        val hit = hitForRunOfLength(59)
        assertEquals("…" + "甲".repeat(20) + "乙".repeat(40) + "…", hit.snippet)

        val highlight = hit.highlights.single()
        assertEquals(
            "乙".repeat(40),
            hit.snippet.substring(highlight.start, highlight.endExclusive),
        )
        // Twenty lead-in characters stand between the opening ellipsis and the mark.
        assertEquals(20, highlight.start - 1)
    }

    @Test
    fun `a hit that exactly fills the window has no lead-in`() {
        val hit = hitForRunOfLength(60)
        assertEquals("…" + "乙".repeat(60) + "…", hit.snippet)
        // Not one character of lead-in may survive at the boundary itself.
        assertFalse(hit.snippet.contains("甲"))
        // The boundary is only pinned down if the two sides of it disagree.
        assertNotEquals(hitForRunOfLength(59).snippet, hit.snippet)

        val highlight = hit.highlights.single()
        assertEquals(1, highlight.start)
        assertEquals(60, highlight.endExclusive - highlight.start)
    }

    @Test
    fun `a hit longer than the window is clamped to what is shown`() {
        val longer = hitForRunOfLength(61)
        val exact = hitForRunOfLength(60)
        // The 61st character lands outside the window, so §4.6 clamps the mark to it
        // and both cases end up showing exactly the same thing.
        assertEquals(exact.snippet, longer.snippet)
        assertEquals(exact.highlights, longer.highlights)
    }

    @Test
    fun `ellipses appear only where text was actually cut`() {
        val whole = searchConversations("北京", listOf(thread(said("我要去北京"))))
            .single().hits.single()
        assertEquals("我要去北京", whole.snippet)
        assertFalse(whole.snippet.contains("…"))

        // The other direction belongs in the same method: an implementation that pads
        // every snippet unconditionally satisfies one half and fails the other, and
        // keeping the two halves apart is what let that go unnoticed.
        val cropped = searchConversations(
            "北京",
            listOf(thread(replied("測".repeat(100) + "北京" + "尾".repeat(60)))),
        ).single().hits.single()
        assertTrue(cropped.snippet.startsWith("…"))
        assertTrue(cropped.snippet.endsWith("…"))
    }

    // ---- what does not take part in matching ---------------------------------

    @Test
    fun `persona and model names are not searched`() {
        val threads = listOf(threadWithOpener(Persona.Teacher, said("這題我想不通")))
        val only = threads.single()
        // Both strings are printed on the group header (§3.8) but neither is message text.
        assertEquals("老師", only.persona.displayName)
        assertEquals("Qwen2.5 0.5B", only.model.displayName)
        assertFalse(only.messages.any { it.text.contains("老師") })
        assertFalse(only.messages.any { it.text.contains("Qwen", ignoreCase = true) })

        // "qwen" as well as "Qwen": case folding is §1.4, so an implementation that only
        // ever compares lower-case would slip past half of this on its own.
        listOf("老師", "Qwen", "qwen").forEach { query ->
            assertTrue(query, searchConversations(query, threads).isEmpty())
        }
        // Without the positive half, an implementation that always returns nothing passes.
        assertNotNull(searchConversations("想不通", threads).singleOrNull())
    }

    // ---- what crosses the layer boundary (§A.4) ------------------------------

    @Test
    fun `a hit carries the message itself, not just its text`() {
        val message = ChatMessage(
            id = 77L,
            author = Author.Assistant,
            text = "我要去北京",
            createdAt = 4_242L,
        )
        val found = searchConversations("北京", listOf(thread(message))).single()
        val hit = found.hits.single()

        // Four things have to survive the trip. The first is the one nothing else was
        // asserting: swap the message for its text and no other test turns red.
        assertEquals(Author.Assistant, hit.message.author)
        assertEquals(4_242L, hit.message.createdAt)
        assertEquals("我要去北京", hit.snippet)
        assertEquals(1, hit.highlights.size)
        assertEquals(1, found.matchedMessageCount)
    }
}
