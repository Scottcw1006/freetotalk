package com.example.demo.data

import android.content.Context
import com.example.demo.chat.Conversation

/**
 * The one place the chat layer asks about conversations, so that a ViewModel talks to an
 * idea — where threads come from and go — rather than holding a file-backed store itself.
 *
 * That layering is the whole reason this class exists. It buys no speed and no new
 * capability: every method here forwards straight to [ConversationStore], and there is
 * exactly one caller. Anyone tempted to justify it by some feature it enables should
 * stop, because there isn't one.
 *
 * Deliberately kept free of state — no cache, no flow, no invalidation. "Refreshed by
 * reading everything again" is currently the caller's guarantee, and quietly moving that
 * decision in here would change when new data becomes visible without changing any call
 * site. If caching does arrive later, it arrives with tests; today there are none,
 * because there is no logic to test.
 *
 * There is deliberately no search or query method. Searching happens over what the user
 * can see on screen, and this class only knows what reached the disk — it cannot see the
 * open thread (which may never be saved) or a reply that is still streaming in.
 */
class ConversationRepository(context: Context) {

    private val store = ConversationStore(context)

    suspend fun all(): List<Conversation> = store.loadAll()

    /** Every caller that wants one thread went through the whole list to find it. */
    suspend fun find(id: String): Conversation? = all().firstOrNull { it.id == id }

    suspend fun save(conversation: Conversation) = store.save(conversation)

    suspend fun delete(id: String) = store.delete(id)
}
