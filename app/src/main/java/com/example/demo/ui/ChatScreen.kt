package com.example.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.demo.chat.Author
import com.example.demo.chat.ChatMessage
import com.example.demo.chat.ChatUiState
import com.example.demo.chat.ChatViewModel
import com.example.demo.chat.Conversation
import com.example.demo.chat.EngineStatus
import com.example.demo.llm.ModelSpec
import com.example.demo.persona.Persona
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatApp(viewModel: ChatViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var pickingPersona by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf(false) }
    // Saved, unlike `comparing`: a rotation should not throw the user out of a search
    // they are in the middle of typing. The query itself lives in the ViewModel, so the
    // two survive the same turn of the screen.
    var searching by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // Kept above the screens that replace the chat, so a notice raised while searching
    // waits and is shown on the way back rather than being lost.
    val notice = state.notices.firstOrNull()
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        snackbar.showSnackbar(
            message = notice.message,
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        viewModel.noticeShown(notice)
    }

    if (comparing) {
        CompareScreen(onBack = { comparing = false })
        return
    }

    if (searching) {
        SearchScreen(
            state = state,
            onQueryChange = viewModel::onSearchQueryChange,
            onFocusLost = viewModel::onSearchFocusLost,
            // Compared by id rather than by any property that merely correlates with
            // "this is the open one": a thread carrying only assistant messages can
            // reach the history list too, and would be misread by anything else.
            onOpen = { conversationId ->
                if (conversationId != state.conversation.id) {
                    viewModel.openThread(conversationId)
                }
                viewModel.clearSearch()
                searching = false
            },
            onBack = {
                viewModel.clearSearch()
                searching = false
            },
        )
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HistoryDrawer(
                state = state,
                isOpening = drawerState.targetValue == DrawerValue.Open,
                onOpen = { id ->
                    viewModel.openThread(id)
                    scope.launch { drawerState.close() }
                },
                onDelete = viewModel::deleteThread,
                onNewThread = {
                    viewModel.startNewThread()
                    scope.launch { drawerState.close() }
                },
                onPickModel = { spec ->
                    viewModel.switchModel(spec)
                    scope.launch { drawerState.close() }
                },
                onCompare = {
                    comparing = true
                    scope.launch { drawerState.close() }
                },
                onSearch = {
                    searching = true
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "歷史紀錄")
                        }
                    },
                    title = {
                        Column {
                            Text("${state.conversation.persona.emoji} ${state.conversation.persona.displayName}")
                            Text(
                                text = statusLabel(state),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        TextButton(onClick = { pickingPersona = true }) { Text("換人") }
                    },
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when (val status = state.status) {
                    is EngineStatus.Failed -> ErrorPane(status.message)
                    else -> ChatPane(
                        state = state,
                        onSend = viewModel::send,
                        onStop = viewModel::stop,
                        loading = status.takeIf { it !is EngineStatus.Ready },
                    )
                }
            }
        }
    }

    if (pickingPersona) {
        ModalBottomSheet(
            onDismissRequest = { pickingPersona = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            PersonaPicker(
                current = state.conversation.persona,
                onPick = { persona ->
                    pickingPersona = false
                    viewModel.switchPersona(persona)
                },
            )
        }
    }
}

private fun statusLabel(state: ChatUiState): String {
    val model = state.conversation.model.displayName
    return when (state.status) {
        is EngineStatus.Extracting -> "$model・首次使用，正在解壓…"
        EngineStatus.Starting -> "$model・載入中…"
        is EngineStatus.Failed -> "無法啟動"
        EngineStatus.Ready -> if (state.isReplying) "$model・輸入中…" else "$model・離線"
    }
}

// ---- history -----------------------------------------------------------------

@Composable
private fun HistoryDrawer(
    state: ChatUiState,
    isOpening: Boolean,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNewThread: () -> Unit,
    onPickModel: (ModelSpec) -> Unit,
    onCompare: () -> Unit,
    onSearch: () -> Unit,
) {
    ModalDrawerSheet {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text("模型", style = MaterialTheme.typography.titleMedium)
        }
        state.availableModels.forEach { spec ->
            val selected = spec == state.conversation.model
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPickModel(spec) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(spec.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = spec.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selected) {
                    Text(
                        text = "使用中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        TextButton(
            onClick = onCompare,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) { Text("並排比較所有模型") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("歷史紀錄", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(4.dp))
            Text(
                text = "每段對話各自獨立，不會互相影響",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onNewThread,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("開始新的對話")
        }
        TextButton(
            onClick = onSearch,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Icon(Icons.Filled.Search, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("搜尋對話內容")
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        if (state.history.isEmpty()) {
            Text(
                text = "還沒有其他對話。",
                modifier = Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Rows are keyed, so the list holds on to whichever row was first on screen
            // last time — and a thread that has just moved to the top lands above it, out
            // of sight. Newest first is the whole point of the list, so it opens at the top.
            val listState = rememberLazyListState()
            LaunchedEffect(isOpening) {
                if (isOpening) listState.scrollToItem(0)
            }
            LazyColumn(state = listState) {
                items(state.history, key = { it.id }) { thread ->
                    HistoryRow(
                        thread = thread,
                        onOpen = { onOpen(thread.id) },
                        onDelete = { onDelete(thread.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(thread: Conversation, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(thread.persona.emoji, fontSize = 22.sp)
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = thread.persona.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = thread.model.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = shortTime(thread.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = thread.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = thread.preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "刪除這段對話",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val timeOfDay = DateTimeFormatter.ofPattern("HH:mm")
private val dayOfYear = DateTimeFormatter.ofPattern("M/d")

internal fun shortTime(epochMillis: Long): String {
    val moment = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    return if (moment.toLocalDate() == LocalDate.now()) {
        moment.format(timeOfDay)
    } else {
        moment.format(dayOfYear)
    }
}

// ---- persona picker ----------------------------------------------------------

@Composable
private fun PersonaPicker(current: Persona, onPick: (Persona) -> Unit) {
    Column(Modifier.navigationBarsPadding()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("換一個人聊", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(4.dp))
            Text(
                text = "換人格會開一段新的對話，現在這段會留在歷史紀錄裡。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(8.dp))
        Persona.entries.forEach { persona ->
            val selected = persona == current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(persona) }
                    .background(
                        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                    )
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(persona.emoji, fontSize = 26.sp)
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(persona.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = persona.tagline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selected) {
                    Text(
                        text = "目前",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        Spacer(Modifier.size(16.dp))
    }
}

// ---- chat --------------------------------------------------------------------

@Composable
private fun ErrorPane(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("模型啟動失敗", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ChatPane(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    loading: EngineStatus?,
) {
    val listState = rememberLazyListState()
    val messages = state.conversation.messages

    // Jump to the newest message when one arrives — deliberately keyed on how many
    // messages there are, never on their text. Keying on the streaming text re-ran this
    // several times a second, and each run cancelled the previous animation and
    // overrode whatever the reader had just scrolled to by hand.
    LaunchedEffect(state.conversation.id, messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Column(Modifier.fillMaxSize()) {
        if (loading != null) LoadingBanner(loading)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            // Newest first in a reversed layout, so the list is pinned to the bottom by
            // construction. A reply that grows past one screenful keeps its tail in
            // view on its own; scrolling to the last item instead aligned that item's
            // *top* with the viewport, which put the newest text off-screen exactly
            // when there was most of it to read.
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages.asReversed(), key = { it.id }) { message -> MessageBubble(message) }
        }
        HorizontalDivider()
        InputBar(
            enabled = state.canSend,
            isReplying = state.isReplying,
            onSend = onSend,
            onStop = onStop,
        )
    }
}

@Composable
private fun LoadingBanner(status: EngineStatus) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(
            text = when (status) {
                is EngineStatus.Extracting ->
                    "正在把 AI 模型解壓到裝置上（${(status.progress * 100).toInt()}%），只有第一次需要等。"
                else -> "正在載入模型，馬上就好。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.size(8.dp))
        if (status is EngineStatus.Extracting) {
            LinearProgressIndicator(
                progress = { status.progress },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val fromMe = message.author == Author.You
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (fromMe) 18.dp else 4.dp,
        bottomEnd = if (fromMe) 4.dp else 18.dp,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromMe) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = shape,
            color = if (fromMe) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            val ink = if (fromMe) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            val inset = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            when {
                message.text.isEmpty() && message.streaming ->
                    TypingIndicator(Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
                // Only the model writes Markdown. What the user typed is shown as typed.
                fromMe -> Text(
                    text = message.text,
                    modifier = inset,
                    style = MaterialTheme.typography.bodyLarge,
                    color = ink,
                )
                else -> ProvideTextStyle(MaterialTheme.typography.bodyLarge) {
                    ModelMarkdownText(text = message.text, color = ink, modifier = inset)
                }
            }
        }
    }
}

@Composable
private fun TypingIndicator(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(50),
                    )
            )
        }
    }
}

@Composable
private fun InputBar(
    enabled: Boolean,
    isReplying: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    fun submit() {
        if (!enabled) return
        val text = draft
        draft = ""
        onSend(text)
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
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("說點什麼…") },
            maxLines = 5,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            keyboardActions = KeyboardActions(onSend = { submit() }),
        )
        if (isReplying) {
            IconButton(onClick = onStop, modifier = Modifier.padding(bottom = 4.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        } else {
            FilledIconButton(
                onClick = ::submit,
                enabled = enabled && draft.isNotBlank(),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "送出")
            }
        }
    }
}
