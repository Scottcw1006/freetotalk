package com.example.demo.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

/**
 * How long the search field has to sit still before a search starts. Every search reads
 * every saved message off the disk, so one per keystroke would be that cost times the
 * length of the word.
 */
const val SEARCH_SETTLE_MILLIS = 250L

/**
 * What a search came back with, and the query it was for — which is not always what the
 * field holds by now. An empty [query] means no search is showing.
 */
data class SearchOutcome(
    val query: String = "",
    val results: List<ConversationHits> = emptyList(),
)

/**
 * A search is the open thread as it stands in memory plus every other thread as the
 * store hands it over, and both halves go through [searchConversations] — the store is
 * asked for threads, never for matches, because its own idea of matching knows nothing
 * of full-width letters or runs of whitespace and no test would ever see it disagree.
 *
 * - Nothing starts until [query] has sat still for [SEARCH_SETTLE_MILLIS]; whatever it
 *   held on the way there is never searched. Emptying it does not wait: the outcome is
 *   empty there and then, and a search still on its way back is dropped.
 * - Both halves of an outcome are for the same query, and arrive together.
 * - The saved half is read again when the query settles or [storeChanges] says something
 *   was written — not when [open] changes, which a reply streaming in does several times
 *   a second. Only the in-memory half is worked out again for that.
 * - Saved threads arrive a batch at a time from [savedThreads] and only their hits are
 *   kept, so a search never holds every message at once.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
fun searchOutcomes(
    query: Flow<String>,
    open: Flow<Conversation>,
    storeChanges: Flow<*>,
    savedThreads: () -> Flow<List<Conversation>>,
): Flow<SearchOutcome> {
    val settled = query
        .distinctUntilChanged()
        .debounce { if (it.isSearchable) SEARCH_SETTLE_MILLIS else 0L }
    // transformLatest drops a read that is still going when the next one is called for,
    // so an answer can never arrive after a newer one.
    val saved = combine(settled, storeChanges) { settledQuery, _ -> settledQuery }
        .transformLatest { settledQuery ->
            val hits = mutableListOf<ConversationHits>()
            if (settledQuery.isSearchable) {
                savedThreads().collect { batch ->
                    hits += searchConversations(settledQuery, batch).map { it.withoutItsMessages() }
                }
            }
            emit(SearchOutcome(settledQuery, hits))
        }
    return combine(saved, open) { fromStore, openThread ->
        if (!fromStore.query.isSearchable) return@combine SearchOutcome()
        // Which copy of the open thread survives is settled before anything is ordered:
        // the saved one can carry the later time and still be the one with less in it.
        val merged = searchConversations(fromStore.query, listOf(openThread)) +
            fromStore.results.filterNot { it.conversation.id == openThread.id }
        SearchOutcome(fromStore.query, merged.sortedByDescending { it.conversation.updatedAt })
    }.distinctUntilChanged()
}

/** The header of a result is drawn from the thread's own facts; its hits carry the rest. */
private fun ConversationHits.withoutItsMessages(): ConversationHits =
    copy(conversation = conversation.copy(messages = emptyList()))
