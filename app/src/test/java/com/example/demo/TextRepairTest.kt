package com.example.demo

import com.example.demo.chat.repairModelText
import org.junit.Assert.assertEquals
import org.junit.Test

class TextRepairTest {

    @Test
    fun `byte level tokens are decoded back to chinese`() {
        // Captured verbatim from the device: a numbered list where each item's first
        // character arrived as the stand-ins for its UTF-8 bytes.
        assertEquals("1. 北京", "1.ĠåĮĹ京".repairModelText())
        assertEquals("1. 台北", "1.Ġåı°北".repairModelText())
        assertEquals("2. 香港", "2.Ġé¦Ļ港".repairModelText())
    }

    @Test
    fun `chinese that already decoded correctly is untouched`() {
        assertEquals("臺北，台中，台南", "臺北，台中，台南".repairModelText())
    }

    @Test
    fun `latin text is never rewritten`() {
        // Every one of these is a valid run of byte-level stand-ins, so the decoder has
        // to decline them rather than mangle real words.
        assertEquals("café au lait", "café au lait".repairModelText())
        assertEquals("Ärger", "Ärger".repairModelText())
        assertEquals("naïve", "naïve".repairModelText())
        assertEquals("Hello world", "Hello world".repairModelText())
    }

    @Test
    fun `escaped newlines become real ones`() {
        assertEquals("紅\n青", """紅\n青""".repairModelText())
        assertEquals("a\tb", """a\tb""".repairModelText())
        // A leading escape used to survive trim(), because it was never whitespace.
        assertEquals("如果你需要借錢", """\n如果你需要借錢""".repairModelText())
    }

    @Test
    fun `stop tokens are stripped`() {
        assertEquals("你好", "你好<|im_end|>".repairModelText())
        assertEquals("你好", "  你好<|endoftext|>  ".repairModelText())
    }

    @Test
    fun `both repairs apply to one string`() {
        assertEquals("1. 北京\n2. 香港", """1.ĠåĮĹ京\n2.Ġé¦Ļ港""".repairModelText())
    }

    @Test
    fun `markdown hard break markers are dropped`() {
        // Captured from Gemma: it writes Markdown, so lines end with a backslash that
        // means "break here" — redundant once the break itself is a real newline.
        assertEquals("也許是因為：\n*   重點", "也許是因為：\\\n*   重點".repairModelText())
    }

    @Test
    fun `a backslash the model really wrote survives`() {
        // MediaPipe escapes backslashes, so a path the model typed as C:\temp arrives
        // doubled. It has to come back out as the single backslash it started as —
        // and crucially must not be eaten by the hard-break rule, which only fires
        // when a newline follows.
        assertEquals("""路徑 C:\temp 裡""", """路徑 C:\\temp 裡""".repairModelText())
    }
}
