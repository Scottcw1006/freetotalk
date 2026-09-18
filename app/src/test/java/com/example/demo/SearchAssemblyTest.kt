package com.example.demo

import com.example.demo.chat.Author
import com.example.demo.chat.ChatMessage
import com.example.demo.chat.Conversation
import com.example.demo.chat.SearchOutcome
import com.example.demo.chat.SearchUiState
import com.example.demo.chat.searchConversations
import com.example.demo.chat.searchOutcomes
import com.example.demo.llm.ModelSpec
import com.example.demo.persona.Persona
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a search is put together: the open thread as it stands in memory, every other
 * thread as the store hands it over, one set of matching rules for both, and nothing
 * started until the field has sat still. The store here is a fake and the clock belongs
 * to the test.
 *
 * What these cannot see is whether the real store hands over what was saved. That half
 * is only checked on a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchAssemblyTest {

    // ---- fixtures ------------------------------------------------------------

    private var nextId = 0L

    private fun said(text: String, deleted: Boolean = false) =
        ChatMessage(nextId++, Author.You, text, createdAt = 0L, deleted = deleted)

    private fun replied(text: String, streaming: Boolean = false) =
        ChatMessage(nextId++, Author.Assistant, text, createdAt = 0L, streaming = streaming)

    private fun thread(
        vararg messages: ChatMessage,
        id: String,
        updatedAt: Long = 1_000L,
    ) = Conversation(
        id = id,
        persona = Persona.Sibling,
        model = ModelSpec.Qwen05B,
        createdAt = 0L,
        updatedAt = updatedAt,
        messages = messages.toList(),
    )

    /** Stands in for the store: hands its threads over in batches, and notes when it was asked. */
    private inner class FakeStore(
        var threads: List<Conversation> = emptyList(),
        private val answersAfter: (request: Int) -> Long = { 0L },
    ) {
        val askedAt = mutableListOf<Long>()

        fun savedThreads(scope: TestScope): () -> Flow<List<Conversation>> = {
            flow {
                val request = askedAt.size
                askedAt += scope.currentTime
                delay(answersAfter(request))
                threads.chunked(2).forEach { emit(it) }
            }
        }
    }

    private class Search(
        val query: MutableStateFlow<String>,
        val open: MutableStateFlow<Conversation>,
        val storeChanged: MutableSharedFlow<Unit>,
        val outcomes: List<SearchOutcome>,
    ) {
        val latest: SearchOutcome get() = outcomes.last()
        val ids: List<String> get() = latest.results.map { it.conversation.id }
    }

    private fun TestScope.searching(open: Conversation, store: FakeStore, query: String = ""): Search {
        val outcomes = mutableListOf<SearchOutcome>()
        val search = Search(MutableStateFlow(query), MutableStateFlow(open), MutableSharedFlow(), outcomes)
        backgroundScope.launch {
            searchOutcomes(
                query = search.query,
                open = search.open,
                storeChanges = search.storeChanged.onStart { emit(Unit) },
                savedThreads = store.savedThreads(this@searching),
            ).collect { outcomes += it }
        }
        runCurrent()
        return search
    }

    private fun TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    // ---- one set of rules ------------------------------------------------------

    @Test
    fun `a saved thread is matched by the very rules the open one is`() = runTest {
        val saved = thread(
            said("Hello  World"),
            replied("ＡＢＣ"),
            said("北京\n天氣"),
            said("kiwi 收回了", deleted = true),
            replied("kiwi 還在"),
            said("臺北"),
            id = "saved",
        )
        val search = searching(thread(replied("嗨"), id = "open"), FakeStore(listOf(saved)))

        for (query in listOf("hello world", "abc", "北京 天氣")) {
            search.query.value = query
            settle()

            assertEquals(query, search.latest.query)
            assertEquals(query, searchConversations(query, listOf(saved)).single().hits, search.latest.results.single().hits)
        }
        assertEquals("Hello World", search.run {
            query.value = "hello world"
            settle()
            val hit = latest.results.single().hits.single()
            hit.highlights.single().let { hit.snippet.substring(it.start, it.endExclusive) }
        })

        search.query.value = "kiwi"
        settle()
        assertEquals(listOf("kiwi 還在"), search.latest.results.single().hits.map { it.message.text })
        SearchUiState("kiwi", search.latest.results).let {
            assertEquals(1, it.matchedConversationCount)
            assertEquals(1, it.matchedMessageTotal)
        }

        search.query.value = "台"
        settle()
        assertEquals(SearchOutcome("台", emptyList()), search.latest)
    }

    // ---- the open thread is the one in memory ----------------------------------

    @Test
    fun `the open thread is searched along with the saved ones`() = runTest {
        val open = thread(said("北京 開啟中"), id = "open")
        val old = thread(said("北京 歷史"), id = "old")
        val search = searching(open, FakeStore(listOf(old)), query = "北京")
        settle()

        assertEquals(setOf("open", "old"), search.ids.toSet())
    }

    @Test
    fun `threads come back newest content first, the open one in its place among them`() = runTest {
        val open = thread(said("北京 開啟中"), id = "open", updatedAt = 200L)
        val older = thread(said("北京 舊"), id = "older", updatedAt = 100L)
        val newer = thread(said("北京 新"), id = "newer", updatedAt = 300L)
        val search = searching(open, FakeStore(listOf(older, newer)), query = "北京")
        settle()

        assertEquals(listOf("newer", "open", "older"), search.ids)
    }

    /**
     * The open thread is in the store too once it has been saved, so the same id arrives
     * from both sides — and a list keyed by id cannot survive that.
     */
    @Test
    fun `the same thread arriving from both sides appears once, as it stands in memory`() = runTest {
        val open = thread(said("北京 第一句"), said("北京 第二句"), id = "same", updatedAt = 500L)
        val stale = open.copy(messages = open.messages.take(1))
        val search = searching(open, FakeStore(listOf(stale)), query = "北京")
        settle()

        assertEquals(listOf("same"), search.ids)
        assertEquals(2, search.latest.results.single().matchedMessageCount)
    }

    /**
     * The saved copy can carry a newer timestamp than the live one — it is written by a
     * different path. The live copy still wins, because it may hold messages that were
     * never saved or a reply still streaming in. Choosing which copy survives after
     * sorting would keep the saved one, put `same` first and report one hit; this is the
     * only test that separates the two orders.
     */
    @Test
    fun `the in-memory copy wins even when the saved one looks newer`() = runTest {
        val first = said("北京 A")
        val second = said("北京 B")
        val open = thread(first, second, id = "same", updatedAt = 100L)
        val savedLooksNewer = thread(first, id = "same", updatedAt = 900L)
        val other = thread(said("北京 C"), id = "other", updatedAt = 500L)
        val search = searching(open, FakeStore(listOf(savedLooksNewer, other)), query = "北京")
        settle()

        assertEquals(listOf("other", "same"), search.ids)
        assertEquals(2, search.latest.results.last().matchedMessageCount)
    }

    @Test
    fun `an open thread nobody has written in is searched though the store has never heard of it`() = runTest {
        val blank = thread(ChatMessage(0, Author.Assistant, Persona.Sibling.opener), id = "blank", updatedAt = 900L)
        val older = thread(replied(Persona.Sibling.opener), said("舊的"), id = "older", updatedAt = 100L)
        val search = searching(blank, FakeStore(listOf(older)), query = Persona.Sibling.opener.take(2))
        settle()

        assertEquals(listOf("blank", "older"), search.ids)
    }

    @Test
    fun `a reply still streaming in is found by what it says so far, with no typing needed`() = runTest {
        val sent = said("說個故事")
        val open = thread(sent, replied("從前", streaming = true), id = "open")
        val search = searching(open, FakeStore(), query = "從前有座山")
        settle()
        assertEquals(SearchOutcome("從前有座山", emptyList()), search.latest)
        val asked = search.outcomes.size

        search.open.value = open.copy(messages = listOf(sent, replied("從前有座山，山上", streaming = true)))
        runCurrent()

        assertTrue(search.outcomes.size > asked)
        assertEquals(listOf("open"), search.ids)
    }

    // ---- results belong to the query in the field ------------------------------

    @Test
    fun `an answer that comes back late is not shown over a newer one`() = runTest {
        val store = FakeStore(
            threads = listOf(thread(said("h only"), id = "h"), thread(said("he too"), id = "he")),
            answersAfter = { request -> if (request == 0) 5_000L else 10L },
        )
        val search = searching(thread(replied("嗨"), id = "open"), store)

        search.query.value = "h"
        advanceTimeBy(1_000)
        search.query.value = "he"
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(2, store.askedAt.size)
        assertEquals("he", search.latest.query)
        assertEquals(listOf("he"), search.ids)
        assertTrue(search.outcomes.none { it.query == "h" })
    }

    @Test
    fun `an answer that comes back after the field was emptied is not shown`() = runTest {
        val store = FakeStore(listOf(thread(said("hello"), id = "saved")), answersAfter = { 5_000L })
        val search = searching(thread(replied("嗨"), id = "open"), store)

        search.query.value = "hello"
        advanceTimeBy(1_000)
        search.query.value = ""
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(1, store.askedAt.size)
        assertEquals(SearchOutcome(), search.latest)
        assertTrue(search.outcomes.all { it.results.isEmpty() })
    }

    // ---- nothing starts until the field has sat still --------------------------

    @Test
    fun `a search starts once the field has sat still for 250 ms, and only for what it holds then`() = runTest {
        val store = FakeStore(listOf(thread(said("help me"), id = "saved")))
        val search = searching(thread(said("hello"), id = "open", updatedAt = 2_000L), store)

        search.query.value = "h"
        advanceTimeBy(100)
        search.query.value = "he"
        advanceTimeBy(100)
        search.query.value = "hel"
        advanceTimeBy(249)
        runCurrent()

        assertEquals(449L, currentTime)
        assertEquals(emptyList<Long>(), store.askedAt)
        assertEquals(listOf(SearchOutcome()), search.outcomes.distinct())

        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf(450L), store.askedAt)
        assertEquals("hel", search.latest.query)
        assertEquals(listOf("open", "saved"), search.ids)
        assertEquals(listOf("", "hel"), search.outcomes.map { it.query }.distinct())

        settle()
        assertEquals(listOf(450L), store.askedAt)
    }

    @Test
    fun `emptying the field stops the search there and then`() = runTest {
        for (emptied in listOf("", "   ")) {
            val store = FakeStore(listOf(thread(said("hello"), id = "saved")))
            val search = searching(thread(replied("嗨"), id = "open"), store, query = "hello")
            settle()
            assertEquals(listOf("saved"), search.ids)
            val asked = store.askedAt.toList()
            val emptiedAt = currentTime

            search.query.value = emptied
            runCurrent()

            assertEquals(emptiedAt, currentTime)
            assertEquals(SearchOutcome(), search.latest.copy(query = search.latest.query.trim()))
            assertTrue(search.latest.results.isEmpty())
            settle()
            assertEquals(asked, store.askedAt)
        }
    }

    @Test
    fun `a field emptied before it had sat still never reaches the store`() = runTest {
        val store = FakeStore(listOf(thread(said("hello"), id = "saved")))
        val search = searching(thread(replied("嗨"), id = "open"), store)

        search.query.value = "h"
        advanceTimeBy(100)
        search.query.value = ""
        advanceTimeBy(900)
        runCurrent()

        assertEquals(1_000L, currentTime)
        assertEquals(emptyList<Long>(), store.askedAt)
        assertTrue(search.outcomes.all { it.results.isEmpty() && it.query.isEmpty() })
    }

    @Test
    fun `a change in the store is searched again without waiting for the field`() = runTest {
        val store = FakeStore(listOf(thread(said("hello one"), id = "one")))
        val search = searching(thread(replied("嗨"), id = "open"), store, query = "hello")
        settle()
        assertEquals(listOf("one"), search.ids)

        store.threads = store.threads + thread(said("hello two"), id = "two", updatedAt = 2_000L)
        val changedAt = currentTime
        search.storeChanged.emit(Unit)
        runCurrent()

        assertEquals(changedAt, currentTime)
        assertEquals(listOf("two", "one"), search.ids)
    }
}
