package com.example.demo.compare

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.demo.chat.repairModelText
import com.example.demo.llm.LlmEngine
import com.example.demo.llm.ModelSpec
import com.example.demo.llm.ModelStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A neutral voice on purpose. Comparing models through a persona would measure how well
 * each one plays a character, which is a different question from how each one answers.
 */
private const val NEUTRAL_PROMPT = "你是一個有幫助的助理。用繁體中文簡短回答。"

enum class RunState { Waiting, Loading, Generating, Done, Failed }

data class CompareRun(
    val spec: ModelSpec,
    val text: String = "",
    val state: RunState = RunState.Waiting,
    val extractProgress: Float = 0f,
    val loadMillis: Long = 0,
    val generateMillis: Long = 0,
    val tokens: Int = 0,
    val error: String? = null,
) {
    /** Only meaningful once generation finished. */
    val tokensPerSecond: Double?
        get() = if (state == RunState.Done && generateMillis > 0 && tokens > 0) {
            tokens * 1000.0 / generateMillis
        } else {
            null
        }
}

data class CompareUiState(
    val models: List<ModelSpec> = emptyList(),
    val selected: Set<ModelSpec> = emptySet(),
    val runs: List<CompareRun> = emptyList(),
    val running: Boolean = false,
)

class CompareViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = LlmEngine.get(application)

    private val _uiState = MutableStateFlow(
        ModelStore.installed(application).let { installed ->
            CompareUiState(models = installed, selected = installed.toSet())
        }
    )
    val uiState: StateFlow<CompareUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    fun toggle(spec: ModelSpec) {
        if (_uiState.value.running) return
        _uiState.update { state ->
            val next = if (spec in state.selected) state.selected - spec else state.selected + spec
            state.copy(selected = next)
        }
    }

    fun run(prompt: String) {
        val question = prompt.trim()
        val order = _uiState.value.models.filter { it in _uiState.value.selected }
        if (question.isEmpty() || order.isEmpty() || _uiState.value.running) return

        _uiState.update {
            it.copy(running = true, runs = order.map { spec -> CompareRun(spec) })
        }

        job = viewModelScope.launch {
            // Strictly one model at a time: two resident at once is how this app gets
            // killed for memory. The wall-clock cost of the swap is reported per row.
            order.forEach { spec ->
                update(spec) { it.copy(state = RunState.Loading) }
                try {
                    val loadMillis = engine.load(spec) { progress ->
                        update(spec) { it.copy(extractProgress = progress) }
                    }
                    update(spec) { it.copy(state = RunState.Generating, loadMillis = loadMillis) }

                    val answer = StringBuilder()
                    val startedAt = System.currentTimeMillis()
                    engine.reply(spec, NEUTRAL_PROMPT, emptyList(), question).collect { chunk ->
                        when (chunk) {
                            is LlmEngine.ReplyChunk.Partial -> {
                                answer.append(chunk.text)
                                update(spec) { it.copy(text = answer.toString().repairModelText()) }
                            }
                            is LlmEngine.ReplyChunk.Complete -> {
                                answer.setLength(0)
                                answer.append(chunk.text)
                            }
                        }
                    }
                    val elapsed = System.currentTimeMillis() - startedAt
                    val finalText = answer.toString().repairModelText()
                    update(spec) {
                        it.copy(
                            state = RunState.Done,
                            text = finalText,
                            generateMillis = elapsed,
                            tokens = engine.tokenCount(finalText),
                        )
                    }
                } catch (e: Exception) {
                    update(spec) {
                        it.copy(state = RunState.Failed, error = e.message ?: e.toString())
                    }
                }
            }
            _uiState.update { it.copy(running = false) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _uiState.update { state ->
            state.copy(
                running = false,
                runs = state.runs.map {
                    if (it.state == RunState.Loading || it.state == RunState.Generating) {
                        it.copy(state = RunState.Failed, error = "已取消")
                    } else {
                        it
                    }
                },
            )
        }
    }

    private fun update(spec: ModelSpec, transform: (CompareRun) -> CompareRun) {
        _uiState.update { state ->
            state.copy(runs = state.runs.map { if (it.spec == spec) transform(it) else it })
        }
    }
}
