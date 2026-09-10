package com.example.demo

import com.example.demo.chat.Block
import com.example.demo.chat.Span
import com.example.demo.chat.parseModelMarkdown
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelMarkdownTest {

    @Test
    fun `a bullet with a bold lead-in splits correctly`() {
        // Captured from Gemma's teacher persona.
        val blocks = parseModelMarkdown("*   **溝通問題：** 彼此對話的方式不同。")
        assertEquals(
            listOf(
                Block.Bullet(
                    marker = "•",
                    spans = listOf(Span("溝通問題：", bold = true), Span(" 彼此對話的方式不同。")),
                )
            ),
            blocks,
        )
    }

    @Test
    fun `numbered items keep the number the model wrote`() {
        val blocks = parseModelMarkdown("1. 北京\n2) 台北")
        assertEquals(listOf("1.", "2."), blocks.map { (it as Block.Bullet).marker })
    }

    @Test
    fun `plain lines stay one paragraph and blank lines split them`() {
        val blocks = parseModelMarkdown("第一行\n第二行\n\n新的一段")
        assertEquals(2, blocks.size)
        assertEquals(listOf(Span("第一行\n第二行")), (blocks[0] as Block.Paragraph).spans)
        assertEquals(listOf(Span("新的一段")), (blocks[1] as Block.Paragraph).spans)
    }

    @Test
    fun `bold at the start of a line is not mistaken for a bullet`() {
        // "**粗體**" begins with an asterisk but no space follows it.
        val blocks = parseModelMarkdown("**重點** 在這裡")
        assertEquals(
            listOf(Span("重點", bold = true), Span(" 在這裡")),
            (blocks.single() as Block.Paragraph).spans,
        )
    }

    @Test
    fun `an unterminated marker is left exactly as written`() {
        val blocks = parseModelMarkdown("這是 **沒有收尾的粗體")
        assertEquals(
            listOf(Span("這是 **沒有收尾的粗體")),
            (blocks.single() as Block.Paragraph).spans,
        )
    }

    @Test
    fun `a lone asterisk in prose is not a bullet`() {
        val blocks = parseModelMarkdown("價格是 5 * 3 元")
        assertEquals(1, blocks.size)
        assertEquals(listOf(Span("價格是 5 * 3 元")), (blocks.single() as Block.Paragraph).spans)
    }

    @Test
    fun `single asterisks are italics`() {
        val blocks = parseModelMarkdown("understand *why* you feel this way")
        assertEquals(
            listOf(
                Span("understand "),
                Span("why", italic = true),
                Span(" you feel this way"),
            ),
            (blocks.single() as Block.Paragraph).spans,
        )
    }

    @Test
    fun `multiplication is not italics`() {
        val blocks = parseModelMarkdown("價格是 5 * 3 * 2 元")
        assertEquals(listOf(Span("價格是 5 * 3 * 2 元")), (blocks.single() as Block.Paragraph).spans)
    }

    @Test
    fun `bold wins over italic on the same run`() {
        val blocks = parseModelMarkdown("**重點**")
        assertEquals(listOf(Span("重點", bold = true)), (blocks.single() as Block.Paragraph).spans)
    }
}
