package com.example.demo.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * On-device chat, backed entirely by bundled models. No network, no API key.
 *
 * Only one model is resident at a time. Each weighs several hundred megabytes of native
 * memory — the 0.5B alone holds around 1GB of PSS — so keeping two loaded to save a swap
 * is a good way to get killed by the low-memory killer. Switching models unloads first.
 *
 * A single instance is shared by the chat and comparison screens, and [lock] serialises
 * them: MediaPipe will not run two generations at once, and a swap underneath a running
 * generation would be worse.
 */
class LlmEngine private constructor(private val context: Context) {

    private val lock = Mutex()
    private var inference: LlmInference? = null
    private var loaded: ModelSpec? = null

    val current: ModelSpec? get() = loaded

    /**
     * Makes [spec] the resident model, unloading whatever was there. Returns how long it
     * took, which the comparison screen reports — a small model that answers instantly
     * but takes ten seconds to load is a different trade than the number alone suggests.
     */
    suspend fun load(spec: ModelSpec, onExtractProgress: (Float) -> Unit): Long =
        lock.withLock { loadLocked(spec, onExtractProgress) }

    private suspend fun loadLocked(spec: ModelSpec, onExtractProgress: (Float) -> Unit): Long {
        if (loaded == spec && inference != null) {
            onExtractProgress(1f)
            return 0L
        }
        val model = ModelStore.ensureExtracted(context, spec, onExtractProgress)
        return withContext(Dispatchers.IO) {
            inference?.close()
            inference = null
            loaded = null
            val startedAt = System.currentTimeMillis()
            inference = LlmInference.createFromOptions(
                context,
                LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(model.absolutePath)
                    .setMaxTokens(spec.maxTokens)
                    .setMaxTopK(TOP_K)
                    // CPU is slower than GPU but works on every device; the GPU path
                    // fails outright on some drivers with q8 weights.
                    .setPreferredBackend(LlmInference.Backend.CPU)
                    .build()
            )
            loaded = spec
            System.currentTimeMillis() - startedAt
        }
    }

    /**
     * Streams a reply from [spec], in the voice of [systemPrompt], with [history] as
     * context. Loads [spec] first if some other model is resident.
     *
     * Every turn gets a brand new session handed the whole conversation as one prompt,
     * rather than one long-lived session accumulating history in its KV cache. That costs
     * a re-prefill per message — cheap, because these models are built for batched
     * prefill — and buys two things worth more: threads cannot leak into each other, and
     * the persona's system turn is re-stated every time rather than drifting out of the
     * model's attention.
     *
     * [ReplyChunk.Partial] carries a new fragment to append, never the running total.
     * Fragments are only safe to *display*: MediaPipe decodes each one on its own, so a
     * multi-byte character split across two fragments comes out as mojibake — which for
     * Chinese is most characters. The [ReplyChunk.Complete] that ends the stream is
     * decoded in one pass and is the text worth keeping.
     */
    fun reply(
        spec: ModelSpec,
        systemPrompt: String,
        history: List<Turn>,
        userText: String,
    ): Flow<ReplyChunk> = callbackFlow {
        lock.withLock {
            loadLocked(spec) {}
            val engine = requireNotNull(inference)
            val finished = AtomicBoolean(false)

            val session = LlmInferenceSession.createFromOptions(
                engine,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(TOP_K)
                    .setTopP(0.95f)
                    .setTemperature(0.8f)
                    // MediaPipe defaults this to 0, and every message gets a brand new
                    // session — so without a fresh seed the same question returns a
                    // byte-identical answer forever, and asking again is pointless.
                    .setRandomSeed(Random.nextInt())
                    .build()
            )
            session.addQueryChunk(engine.buildPrompt(spec, systemPrompt, history, userText))

            val future = session.generateResponseAsync { fragment, _ ->
                if (fragment != null) trySend(ReplyChunk.Partial(fragment))
            }
            // The progress listener never reports failures, so completion is driven by
            // the future instead — the only place an inference error surfaces.
            future.addListener(
                {
                    finished.set(true)
                    runCatching { future.get() }.fold(
                        onSuccess = { whole ->
                            trySend(ReplyChunk.Complete(whole))
                            close()
                        },
                        onFailure = { close(it) },
                    )
                },
                Executor(Runnable::run)
            )

            awaitClose {
                if (!finished.get()) {
                    runCatching { session.cancelGenerateResponseAsync() }
                    // Cancelling only asks. The native generation thread can still be
                    // running when this returns, and the lock is released right after this
                    // block — at which point a model switch closes the engine that thread
                    // is still reading from, and the app dies in native code. So the
                    // session is only closed, and the lock only released, once the
                    // generation has actually ended.
                    runCatching { future.get(CANCEL_WAIT_SECONDS, TimeUnit.SECONDS) }
                }
                runCatching { session.close() }
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Token count under the resident model, for reporting generation speed. */
    fun tokenCount(text: String): Int =
        runCatching { inference?.sizeInTokens(text) ?: 0 }.getOrDefault(0)

    /**
     * Renders the conversation, dropping the oldest exchanges until the prompt leaves
     * room for the answer. The system turn and the message being replied to are never
     * dropped.
     */
    private fun LlmInference.buildPrompt(
        spec: ModelSpec,
        systemPrompt: String,
        history: List<Turn>,
        userText: String,
    ): String {
        var kept = history
        while (true) {
            val prompt = spec.format.render(systemPrompt, kept, userText)
            if (kept.isEmpty() || sizeInTokens(prompt) <= spec.maxTokens - spec.replyBudget) {
                return prompt
            }
            kept = kept.drop(2) // an exchange is a user turn plus its reply
        }
    }

    sealed interface ReplyChunk {
        /** A freshly generated fragment, for live display only. */
        data class Partial(val text: String) : ReplyChunk
        /** The whole reply, correctly decoded. Replaces everything streamed so far. */
        data class Complete(val text: String) : ReplyChunk
    }

    companion object {
        private const val TOP_K = 40

        /** Far longer than a cancelled generation takes to wind down; only a hung engine hits it. */
        private const val CANCEL_WAIT_SECONDS = 10L

        @Volatile
        private var instance: LlmEngine? = null

        fun get(context: Context): LlmEngine =
            instance ?: synchronized(this) {
                instance ?: LlmEngine(context.applicationContext).also { instance = it }
            }
    }
}
