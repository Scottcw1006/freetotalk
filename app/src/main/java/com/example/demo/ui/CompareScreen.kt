package com.example.demo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.demo.compare.CompareRun
import com.example.demo.compare.CompareViewModel
import com.example.demo.compare.RunState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(onBack: () -> Unit, viewModel: CompareViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var prompt by remember { mutableStateOf("") }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Text("模型比較")
                        Text(
                            text = "同一句話，依序跑過每個模型",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.models.forEach { spec ->
                    FilterChip(
                        selected = spec in state.selected,
                        onClick = { viewModel.toggle(spec) },
                        enabled = !state.running,
                        label = { Text(spec.displayName) },
                    )
                }
            }

            if (state.runs.isEmpty()) {
                Text(
                    text = "一次只有一個模型能常駐記憶體，所以是依序執行——每換一個模型的載入時間會分開列出。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.runs, key = { it.spec.name }) { run -> RunCard(run) }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("要問所有模型的問題…") },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                if (state.running) {
                    OutlinedButton(
                        onClick = viewModel::stop,
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) { Text("停止") }
                } else {
                    Button(
                        onClick = { viewModel.run(prompt) },
                        enabled = prompt.isNotBlank() && state.selected.isNotEmpty(),
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) { Text("執行") }
                }
            }
        }
    }
}

@Composable
private fun RunCard(run: CompareRun) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = run.spec.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                if (run.state == RunState.Loading || run.state == RunState.Generating) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    text = when (run.state) {
                        RunState.Waiting -> "等待中"
                        RunState.Loading -> "載入模型"
                        RunState.Generating -> "生成中"
                        RunState.Done -> "完成"
                        RunState.Failed -> "失敗"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (run.state == RunState.Loading && run.extractProgress in 0.001f..0.999f) {
                Spacer(Modifier.size(8.dp))
                LinearProgressIndicator(
                    progress = { run.extractProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "首次使用，正在解壓模型 ${(run.extractProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (run.text.isNotEmpty() || run.error != null) {
                Spacer(Modifier.size(10.dp))
                Text(
                    text = run.error ?: run.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (run.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }

            if (run.state == RunState.Done) {
                Spacer(Modifier.size(10.dp))
                Text(
                    text = buildString {
                        append("載入 ").append(run.loadMillis / 1000.0).append(" 秒")
                        append("　生成 ").append(run.generateMillis / 1000.0).append(" 秒")
                        append("　").append(run.tokens).append(" tokens")
                        run.tokensPerSecond?.let {
                            append("　").append(String.format("%.1f", it)).append(" tok/s")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
