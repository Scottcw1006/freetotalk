package com.example.demo.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The history list: every stored thread except the open one, recomputed whenever
 * either side changes.
 *
 * It is never read once and kept. A list kept from some earlier moment misses every save
 * that lands after that moment — which is how a thread switched away from mid-reply used
 * to be missing from the list until something else happened to reload it.
 *
 * The open thread is left out because it already lives in [ChatUiState.conversation];
 * listing it twice would be a lie about how many threads exist.
 */
fun <T : HasThreadId> historyOf(
    stored: Flow<List<T>>,
    openId: Flow<String>,
): Flow<List<T>> = combine(stored, openId) { all, open ->
    all.filterNot { it.id == open }
}
