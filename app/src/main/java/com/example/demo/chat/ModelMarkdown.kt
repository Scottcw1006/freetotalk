package com.example.demo.chat

/**
 * The little bit of Markdown these models actually write.
 *
 * Deliberately framework-free so the parsing is unit-testable on its own; the composable
 * that draws the result lives in the ui package. Anything not recognised stays verbatim —
 * a half-written `**` is far better shown as typed than silently swallowed.
 */
internal data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false)

internal sealed interface Block {
    /** Consecutive plain lines, kept together with their line breaks intact. */
    data class Paragraph(val spans: List<Span>) : Block
    /** One list item. [marker] is the bullet or the number the model wrote. */
    data class Bullet(val marker: String, val spans: List<Span>) : Block
}

private val BULLET = Regex("""^\s*[*\-•]\s+(.*)$""")
private val NUMBERED = Regex("""^\s*(\d+)[.)]\s+(.*)$""")
/**
 * Bold first in the alternation so `**x**` never reads as an empty italic wrapping one.
 * Italic additionally refuses a space next to either marker, which keeps arithmetic like
 * `5 * 3 * 2` out of it — the models write that far more often than they write italics.
 */
private val EMPHASIS = Regex("""\*\*(.+?)\*\*|\*(?!\s)([^*]+?)(?<!\s)\*""")

internal fun parseModelMarkdown(text: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val paragraph = mutableListOf<String>()

    fun flush() {
        if (paragraph.isEmpty()) return
        blocks += Block.Paragraph(inlineSpans(paragraph.joinToString("\n")))
        paragraph.clear()
    }

    text.lines().forEach { line ->
        val numbered = NUMBERED.matchEntire(line)
        val bulleted = BULLET.matchEntire(line)
        when {
            line.isBlank() -> flush()
            numbered != null -> {
                flush()
                blocks += Block.Bullet("${numbered.groupValues[1]}.", inlineSpans(numbered.groupValues[2]))
            }
            bulleted != null -> {
                flush()
                blocks += Block.Bullet("•", inlineSpans(bulleted.groupValues[1]))
            }
            else -> paragraph += line
        }
    }
    flush()
    return blocks
}

/**
 * Splits a line into emphasised and plain runs. Only complete pairs count, so an odd
 * number of markers leaves the stray one on screen exactly as the model wrote it.
 */
private fun inlineSpans(line: String): List<Span> {
    val spans = mutableListOf<Span>()
    var cursor = 0
    EMPHASIS.findAll(line).forEach { match ->
        if (match.range.first > cursor) {
            spans += Span(line.substring(cursor, match.range.first))
        }
        val bold = match.groupValues[1]
        spans += if (bold.isNotEmpty()) {
            Span(bold, bold = true)
        } else {
            Span(match.groupValues[2], italic = true)
        }
        cursor = match.range.last + 1
    }
    if (cursor < line.length) spans += Span(line.substring(cursor))
    return spans.ifEmpty { listOf(Span(line)) }
}
