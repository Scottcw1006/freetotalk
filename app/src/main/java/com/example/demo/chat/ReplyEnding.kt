package com.example.demo.chat

/**
 * How a reply stopped arriving. There are five ways, and each one leaves the reader
 * looking at something different.
 *
 * Before this type existed, three of them were decided where the stream finished, a
 * fourth in the block that catches everything, and the fifth — the user pressing stop —
 * was never expressed anywhere. Cancelling the reply threw, the catch-everything block
 * caught it, and the whole partial reply became "出錯了：StandaloneCoroutine was
 * cancelled". Reproduction and diagnosis: docs/specs/stop-generation/findings.md.
 *
 * A sixth way to end will not compile until someone says what it leaves behind.
 */
sealed interface ReplyEnding {
    /** The model said what it had to say. */
    data object Complete : ReplyEnding

    /** The reply ran into the character cap. */
    data object LengthCap : ReplyEnding

    /** The model started saying the same thing over and over. */
    data object RepetitionLoop : ReplyEnding

    /**
     * The user pressed stop. Deliberately not a kind of failure: they said "that's
     * enough", which is not the same as "this went wrong".
     */
    data object UserStopped : ReplyEnding

    /** Inference itself broke. [message] is what it said. */
    data class Failed(val message: String) : ReplyEnding
}

/** What to do with the reply bubble once it has stopped growing. */
sealed interface ReplyOutcome {
    data class Keep(val text: String) : ReplyOutcome

    /** Take the bubble away entirely — there is nothing in it worth keeping. */
    data object Drop : ReplyOutcome
}

/**
 * What the reply is left holding, given the text that streamed in and how it ended.
 *
 * [streamed] is the text as the reader last saw it, already repaired. Nothing here
 * rewrites it: a mark may be appended, and the repetition case trims the tail it is
 * about to describe, but no ending reflows, truncates or completes what is already on
 * screen. Pressing stop especially must not — the reader chose that moment, and the
 * text they were looking at is the one they chose to keep.
 */
fun finalReply(streamed: String, ending: ReplyEnding): ReplyOutcome = when (ending) {
    is ReplyEnding.Failed -> ReplyOutcome.Keep("出錯了：${ending.message}")

    // A bubble holding nothing but "…（已停止）" would tell the reader something they
    // already know — they are the one who stopped it. Their own message stays; this
    // empty answer to it does not.
    ReplyEnding.UserStopped ->
        if (streamed.isBlank()) ReplyOutcome.Drop
        else ReplyOutcome.Keep(streamed + "…（已停止）")

    ReplyEnding.Complete -> ReplyOutcome.Keep(streamed.orSaySoNicely())

    ReplyEnding.LengthCap -> ReplyOutcome.Keep(streamed.orSaySoNicely() + "…（已達長度上限）")

    // Showing the whole loop helps nobody; keep just enough for the reader to see what
    // happened.
    ReplyEnding.RepetitionLoop ->
        ReplyOutcome.Keep(streamed.orSaySoNicely().withoutRepeatingTail() + "…（模型卡在重複迴圈，已中斷）")
}

/**
 * An empty answer the model arrived at by itself is still an answer, so it gets words
 * rather than an empty bubble. [ReplyEnding.UserStopped] deliberately does not come
 * through here: nothing arrived because the reader cut it short, and saying "nothing
 * came back, try again" would blame the model for their decision.
 */
private fun String.orSaySoNicely(): String = ifBlank { "（這次沒有產生回覆，再說一次試試）" }
