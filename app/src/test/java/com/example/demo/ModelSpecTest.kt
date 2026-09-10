package com.example.demo

import com.example.demo.llm.ModelSpec
import com.example.demo.llm.PromptFormat
import com.example.demo.llm.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a conversation is packed into the text a model actually receives.
 *
 * Nothing here is cosmetic: feed a model the wrong turn markers and it stops answering
 * and starts continuing your text instead — which the reader sees as the assistant
 * talking to itself, or writing their side of the conversation for them.
 */
class ModelSpecTest {

    private val system = "你是使用者的姊姊。"

    // ---- picking a model -----------------------------------------------------

    /**
     * The stored name comes off disk, where a model that has since been removed or
     * renamed can still be recorded. Falling back keeps the app opening.
     */
    @Test
    fun `an unknown or missing model name falls back to the default`() {
        assertEquals(ModelSpec.Default, ModelSpec.of(null))
        assertEquals(ModelSpec.Default, ModelSpec.of(""))
        assertEquals(ModelSpec.Default, ModelSpec.of("SomeModelWeDeleted"))
    }

    @Test
    fun `a known model name comes back as itself`() {
        ModelSpec.entries.forEach { spec ->
            assertEquals(spec, ModelSpec.of(spec.name))
        }
    }

    /**
     * The history drawer and search group headers label threads by display name, and the
     * model picker lists them. Two models sharing a name would make those unreadable.
     */
    @Test
    fun `every model is distinguishable`() {
        assertEquals(ModelSpec.entries.size, ModelSpec.entries.map { it.displayName }.toSet().size)
        assertEquals(ModelSpec.entries.size, ModelSpec.entries.map { it.assetName }.toSet().size)
    }

    /** Room has to be left for the answer, or the reply is cut off before it starts. */
    @Test
    fun `the reply budget is a third of the window`() {
        ModelSpec.entries.forEach { spec ->
            assertEquals(spec.maxTokens / 3, spec.replyBudget)
            assertTrue("${spec.name} left no room to answer", spec.replyBudget > 0)
        }
    }

    // ---- ChatMl --------------------------------------------------------------

    @Test
    fun `ChatMl gives the system text its own turn`() {
        val prompt = PromptFormat.ChatMl.render(system, emptyList(), "在嗎")

        assertTrue(prompt.contains("<|im_start|>system\n$system<|im_end|>"))
    }

    @Test
    fun `ChatMl marks who said what and ends waiting for the assistant`() {
        val prompt = PromptFormat.ChatMl.render(
            system,
            listOf(Turn(fromUser = true, text = "早"), Turn(fromUser = false, text = "早安")),
            "在嗎",
        )

        assertTrue(prompt.contains("<|im_start|>user\n早<|im_end|>"))
        assertTrue(prompt.contains("<|im_start|>assistant\n早安<|im_end|>"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    // ---- Gemma ---------------------------------------------------------------

    /**
     * Gemma has no system role at all — its own chat template folds the system text into
     * the first user turn. Emitting a system turn here would be text the model has never
     * seen in training, and it answers as if the persona were never set.
     */
    @Test
    fun `Gemma has no system turn and folds the system text into the first user turn`() {
        val prompt = PromptFormat.Gemma.render(system, emptyList(), "在嗎")

        assertFalse(prompt.contains("system"))
        assertTrue(prompt.startsWith("<start_of_turn>user\n$system\n\n在嗎"))
    }

    /** The .task bundle prepends <bos>; a second one shifts every trained position. */
    @Test
    fun `Gemma emits no bos marker of its own`() {
        val prompt = PromptFormat.Gemma.render(system, emptyList(), "在嗎")

        assertFalse(prompt.contains("<bos>"))
    }

    @Test
    fun `Gemma calls the assistant model and ends waiting for it`() {
        val prompt = PromptFormat.Gemma.render(
            system,
            listOf(Turn(fromUser = true, text = "早"), Turn(fromUser = false, text = "早安")),
            "在嗎",
        )

        assertTrue(prompt.contains("<start_of_turn>model\n早安<end_of_turn>"))
        assertFalse(prompt.contains("assistant"))
        assertTrue(prompt.endsWith("<start_of_turn>model\n"))
    }

    /** Only the first turn carries the system text, or the persona is repeated at it. */
    @Test
    fun `Gemma states the persona once, not once per turn`() {
        val prompt = PromptFormat.Gemma.render(
            system,
            listOf(Turn(fromUser = true, text = "早"), Turn(fromUser = false, text = "早安")),
            "在嗎",
        )

        assertEquals(1, prompt.windowed(system.length).count { it == system })
    }

    // ---- both ----------------------------------------------------------------

    /**
     * The message the reader just typed has to arrive last. Dropping it, or putting it
     * before the history, makes the model answer the wrong thing entirely.
     */
    @Test
    fun `the new message always comes last`() {
        val history = listOf(
            Turn(fromUser = true, text = "第一句"),
            Turn(fromUser = false, text = "回應第一句"),
        )

        PromptFormat.entries.forEach { format ->
            val prompt = format.render(system, history, "最新這句")

            assertTrue("$format dropped the new message", prompt.contains("最新這句"))
            assertTrue(
                "$format put the new message before the history",
                prompt.indexOf("最新這句") > prompt.indexOf("回應第一句"),
            )
        }
    }

    @Test
    fun `an empty history still produces a usable prompt`() {
        PromptFormat.entries.forEach { format ->
            val prompt = format.render(system, emptyList(), "第一次說話")

            assertTrue("$format lost the system text", prompt.contains(system))
            assertTrue("$format lost the message", prompt.contains("第一次說話"))
        }
    }
}
