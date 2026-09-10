package com.example.demo.llm

/**
 * Every model the app can run, and how to talk to it.
 *
 * The prompt format is not cosmetic: feed a model the wrong turn markers and it stops
 * behaving like an assistant and starts continuing your text instead.
 */
enum class ModelSpec(
    val displayName: String,
    val subtitle: String,
    val assetName: String,
    val format: PromptFormat,
    /** Bounded by the KV cache the .task file was built with. */
    val maxTokens: Int,
) {
    Gemma1B(
        displayName = "Gemma3 1B",
        subtitle = "q8 精度，答得最準；預設",
        assetName = "gemma3-1b.task",
        format = PromptFormat.Gemma,
        // The q8 build is packaged with a 1280-entry KV cache, same as the others.
        // Asking for more than the file was built with fails at load time.
        maxTokens = 1280,
    ),
    Qwen05B(
        displayName = "Qwen2.5 0.5B",
        subtitle = "中文流暢、速度快，但常記錯事實",
        assetName = "model.task",
        format = PromptFormat.ChatMl,
        maxTokens = 1280,
    ),
    SmolLm135M(
        displayName = "SmolLM 135M",
        subtitle = "極小極快，品質明顯較差",
        assetName = "smollm.task",
        format = PromptFormat.ChatMl,
        maxTokens = 1280,
    );

    /** Room reserved for the answer once the prompt is packed in. */
    val replyBudget: Int get() = maxTokens / 3

    companion object {
        val Default = Gemma1B
        fun of(name: String?): ModelSpec = entries.firstOrNull { it.name == name } ?: Default
    }
}

/** Turns handed back to a model as context. */
data class Turn(val fromUser: Boolean, val text: String)

enum class PromptFormat {
    /** Qwen and SmolLM: a real system turn, assistant role called "assistant". */
    ChatMl {
        override fun render(system: String, history: List<Turn>, userText: String) = buildString {
            append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
            (history + Turn(fromUser = true, text = userText)).forEach { turn ->
                append(if (turn.fromUser) "<|im_start|>user\n" else "<|im_start|>assistant\n")
                append(turn.text).append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }
    },

    /**
     * Gemma has no system role at all — its own chat template folds the system text into
     * the first user turn, and calls the assistant "model". Emitting a system turn here
     * would just be text the model has never seen in training.
     *
     * No <bos> either: the .task bundle prepends it, and a second one shifts every
     * position the model was trained on.
     */
    Gemma {
        override fun render(system: String, history: List<Turn>, userText: String) = buildString {
            (history + Turn(fromUser = true, text = userText)).forEachIndexed { index, turn ->
                append(if (turn.fromUser) "<start_of_turn>user\n" else "<start_of_turn>model\n")
                if (index == 0) append(system).append("\n\n")
                append(turn.text).append("<end_of_turn>\n")
            }
            append("<start_of_turn>model\n")
        }
    };

    abstract fun render(system: String, history: List<Turn>, userText: String): String
}
