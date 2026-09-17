package com.example.demo

import com.example.demo.chat.Author
import com.example.demo.chat.ChatMessage
import com.example.demo.chat.Conversation
import com.example.demo.chat.historyOf
import com.example.demo.llm.ModelSpec
import com.example.demo.persona.Persona
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The history list is worked out continuously from what is stored and which thread is
 * open, never read once and kept. A list kept from an earlier moment is how a thread
 * switched away from mid-reply went missing: its save landed after the list was read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryListTest {

    private fun thread(id: String, text: String = id) = Conversation(
        id = id,
        persona = Persona.Sibling,
        model = ModelSpec.Gemma1B,
        createdAt = 0L,
        updatedAt = 0L,
        messages = listOf(ChatMessage(0, Author.You, text, createdAt = 0L)),
    )

    @Test
    fun `the list is what is stored minus the open thread`() = runTest {
        val a = thread("a")
        val b = thread("b")
        val outputs = mutableListOf<List<Conversation>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            historyOf(MutableStateFlow(listOf(a, b)), MutableStateFlow("a")).toList(outputs)
        }

        assertEquals(listOf(b), outputs.last())
    }

    @Test
    fun `every change to what is stored, and to the open thread, updates the list`() = runTest {
        val a = thread("a")
        val b = thread("b")
        val c = thread("c")
        val stored = MutableStateFlow(listOf(a, b))
        val open = MutableStateFlow("a")
        val outputs = mutableListOf<List<Conversation>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            historyOf(stored, open).toList(outputs)
        }
        assertEquals(listOf(b), outputs.last())

        stored.value = listOf(a, b, c)
        assertEquals("a thread was added", listOf(b, c), outputs.last())

        val b2 = thread("b", text = "b, rewritten")
        stored.value = listOf(a, b2, c)
        assertEquals("a thread's content changed", listOf(b2, c), outputs.last())

        stored.value = listOf(a, b2)
        assertEquals("a thread was deleted", listOf(b2), outputs.last())

        open.value = "b"
        assertEquals("the open thread changed", listOf(a), outputs.last())
    }

    @Test
    fun `a thread saved after the switch still reaches the list`() = runTest {
        val a = thread("a")
        val stored = MutableStateFlow(emptyList<Conversation>())
        val open = MutableStateFlow("a")
        val outputs = mutableListOf<List<Conversation>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            historyOf(stored, open).toList(outputs)
        }

        open.value = "n"
        stored.value = listOf(a)

        assertEquals(listOf(a), outputs.last())
    }
}
