package com.example.demo

import com.example.demo.chat.ReplyEnding
import com.example.demo.chat.ReplyOutcome
import com.example.demo.chat.finalReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a reply is left holding, for each of the five ways it can end.
 *
 * The case that started this file is `a stopped reply keeps exactly what was on screen`:
 * pressing stop used to replace the whole partial reply with
 * "出錯了：StandaloneCoroutine was cancelled". Reproduction steps, the failing output and
 * the diagnosis are in docs/specs/stop-generation/findings.md.
 *
 * The marks are written out as literals rather than referring to whatever constant the
 * production code uses. Sharing a constant would make these tests agree with any future
 * edit to it, which is the opposite of what they are for.
 */
class ReplyEndingTest {

    // ---- the user pressed stop -----------------------------------------------

    @Test
    fun `a stopped reply keeps exactly what was on screen and says it was cut short`() {
        // The reproduction from findings.md: "tell me a long story about a cat", stopped
        // three to five seconds in, with this much of the story already on screen.
        val onScreen = "Once upon a time there was a cat who lived on the roof of a " +
            "small town bakery, and every morning she"

        assertEquals(
            ReplyOutcome.Keep(
                "Once upon a time there was a cat who lived on the roof of a " +
                    "small town bakery, and every morning she…（已停止）",
            ),
            finalReply(onScreen, ReplyEnding.UserStopped),
        )
    }

    @Test
    fun `stopping appends the mark and changes nothing else`() {
        // Trailing space, a newline and an unfinished word: the reader chose this moment,
        // so this is the text they chose to keep. Nothing may be trimmed, reflowed or
        // completed on their behalf.
        val onScreen = "第一行\n第二行還沒寫完 "

        assertEquals(
            ReplyOutcome.Keep("第一行\n第二行還沒寫完 …（已停止）"),
            finalReply(onScreen, ReplyEnding.UserStopped),
        )
    }

    @Test
    fun `stopping before anything arrived leaves nothing behind`() {
        // A bubble holding only the mark says nothing the reader does not already know.
        assertEquals(ReplyOutcome.Drop, finalReply("", ReplyEnding.UserStopped))
        assertEquals(ReplyOutcome.Drop, finalReply("   ", ReplyEnding.UserStopped))
    }

    @Test
    fun `a stopped reply is not an error`() {
        val stopped = finalReply("已經講到一半", ReplyEnding.UserStopped) as ReplyOutcome.Keep
        // The strings the old behaviour produced, and the shapes near it.
        listOf("出錯了", "cancel", "Cancel", "Exception", "Job", "Coroutine").forEach { word ->
            assertTrue(word, !stopped.text.contains(word))
        }
    }

    // ---- the other four endings ----------------------------------------------

    @Test
    fun `a reply that ended by itself is left exactly as it came`() {
        assertEquals(
            ReplyOutcome.Keep("好的，沒問題。"),
            finalReply("好的，沒問題。", ReplyEnding.Complete),
        )
    }

    @Test
    fun `an empty reply the model arrived at by itself still says something`() {
        assertEquals(
            ReplyOutcome.Keep("（這次沒有產生回覆，再說一次試試）"),
            finalReply("", ReplyEnding.Complete),
        )
    }

    @Test
    fun `the length cap mark is unchanged`() {
        assertEquals(
            ReplyOutcome.Keep("講了很多話…（已達長度上限）"),
            finalReply("講了很多話", ReplyEnding.LengthCap),
        )
    }

    @Test
    fun `the repetition mark is unchanged, and the chant is still cut down`() {
        // "ab" four times over: the tail collapses to three rounds, then the mark goes on.
        assertEquals(
            ReplyOutcome.Keep("ababab…（模型卡在重複迴圈，已中斷）"),
            finalReply("abababab", ReplyEnding.RepetitionLoop),
        )
    }

    @Test
    fun `a real failure still reads as an error`() {
        // Only the user pressing stop was taken off this path; a failure that is a
        // failure must still look like one.
        assertEquals(
            ReplyOutcome.Keep("出錯了：out of memory"),
            finalReply("寫到一半", ReplyEnding.Failed("out of memory")),
        )
    }
}
