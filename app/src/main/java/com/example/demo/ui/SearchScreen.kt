package com.example.demo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.demo.chat.Author
import com.example.demo.chat.ChatUiState
import com.example.demo.chat.Conversation
import com.example.demo.chat.Highlight
import com.example.demo.chat.MessageHit
import com.example.demo.persona.Persona

/**
 * A screen of its own rather than a section of the drawer, because the input has to sit
 * at the very top of whatever scrolls. Filtering as you type is only useful if you can
 * see the results while typing, and the keyboard takes the bottom half of the screen —
 * so every fixed pixel above the input is a pixel the results do not get. In the drawer
 * the input landed halfway down, and with the keyboard up there was nothing left.
 *
 * Stateless on purpose: it holds no ViewModel of its own, so the thread list it searches
 * is the same one the chat screen is showing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: ChatUiState,
    onQueryChange: (String) -> Unit,
    onFocusLost: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

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
                    TextField(
                        value = state.search.query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { if (!it.isFocused) onFocusLost() },
                        placeholder = { Text("搜尋對話內容…") },
                        trailingIcon = {
                            if (state.search.query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChange("") }) {
                                    Icon(Icons.Filled.Close, contentDescription = "清除搜尋")
                                }
                            }
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val results = state.search.results
            when {
                !state.search.isActive -> Notice("搜尋所有對話的訊息內容")
                results.isEmpty() -> Notice("找不到符合「${state.search.query}」的訊息")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "summary") {
                        Text(
                            text = "${results.size} 段對話・${results.sumOf { it.matchedMessageCount }} 則訊息",
                            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    results.forEach { group ->
                        val threadId = group.conversation.id
                        item(key = "head-$threadId") { SearchGroupHeader(group.conversation) }
                        // Message ids restart at 0 in every thread, so the thread has to
                        // be part of the key — openers are id 0 and match constantly.
                        items(group.hits, key = { "$threadId#${it.message.id}" }) { hit ->
                            SearchHitRow(
                                hit = hit,
                                persona = group.conversation.persona,
                                onOpen = { onOpen(threadId) },
                            )
                        }
                        val hidden = group.matchedMessageCount - group.hits.size
                        if (hidden > 0) {
                            item(key = "more-$threadId") {
                                Text(
                                    text = "還有 $hidden 則符合",
                                    modifier = Modifier.padding(start = 56.dp, bottom = 10.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Text(
            text = text,
            modifier = Modifier.padding(32.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Deliberately not [Conversation.title]: a thread holding nothing but its opener is
 * searchable now, and its title reads "還沒說話" — a sentence that is neither the match
 * nor even something search looks at. Persona, model and time are true of every thread.
 */
@Composable
private fun SearchGroupHeader(thread: Conversation) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(thread.persona.emoji, fontSize = 18.sp)
        Spacer(Modifier.size(10.dp))
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
}

@Composable
private fun SearchHitRow(hit: MessageHit, persona: Persona, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 56.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (hit.message.author == Author.You) "你" else persona.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = hit.snippet.withHighlights(hit.highlights),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The ranges arrive already computed; nothing here decides what matched. If this function
 * ever needs to look at the query, the matching rules have leaked out of the one place
 * tests can reach them.
 */
@Composable
private fun String.withHighlights(highlights: List<Highlight>): AnnotatedString {
    val accent = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        append(this@withHighlights)
        highlights.forEach { range ->
            addStyle(
                SpanStyle(color = accent, fontWeight = FontWeight.Bold),
                range.start,
                range.endExclusive,
            )
        }
    }
}
