package com.example.demo

import com.example.demo.chat.isLoopingOnItself
import com.example.demo.chat.withoutRepeatingTail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DegenerationTest {

    /** The real reply that prompted this, shortened to its shape. */
    private val chanting = "你的感受當然值得被尊重，但這些感受並不一定代表你" + "100% ".repeat(40)

    @Test
    fun `a chanting tail is detected`() {
        assertTrue(chanting.isLoopingOnItself())
        assertTrue(("哈".repeat(80)).isLoopingOnItself())
        assertTrue(("好的，我明白了。".repeat(20)).isLoopingOnItself())
    }

    @Test
    fun `ordinary writing is never flagged`() {
        val advice = "先給彼此一點時間冷靜下來，等情緒過去了再找個安靜的地方談。" +
            "說的時候盡量講自己的感受，而不是指責對方做了什麼，這樣對話比較不會又吵起來。" +
            "如果你們的友情夠深，多半撐得過這一次。"
        assertFalse(advice.isLoopingOnItself())
        assertFalse("短短一句話。".isLoopingOnItself())
        assertFalse("".isLoopingOnItself())
    }

    @Test
    fun `repeated words inside real sentences are not a loop`() {
        // Emphasis and lists repeat, but never for sixty straight characters.
        val emphatic = "真的真的真的很重要，我再說一次：先冷靜，先冷靜，然後再談這件事情該怎麼收尾比較好。"
        assertFalse(emphatic.isLoopingOnItself())
    }

    @Test
    fun `the chant is trimmed down but its shape survives`() {
        val trimmed = chanting.withoutRepeatingTail()
        assertTrue("開頭的正常內容要保留", trimmed.startsWith("你的感受當然值得被尊重"))
        assertTrue("要短得多", trimmed.length < chanting.length / 3)
        assertTrue("仍看得出在重複", trimmed.contains("100%"))
    }

    @Test
    fun `text with no repeating tail is returned untouched`() {
        val clean = "先冷靜下來，再找機會談談。"
        assertEquals(clean, clean.withoutRepeatingTail())
    }
}
