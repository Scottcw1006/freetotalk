package com.example.demo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.demo.chat.Block
import com.example.demo.chat.Span
import com.example.demo.chat.parseModelMarkdown

/**
 * Renders the light Markdown the models write — bold runs and list items — instead of
 * showing the raw asterisks. Only assistant text goes through this: whatever the user
 * typed is their own words and gets shown exactly as typed.
 */
@Composable
fun ModelMarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseModelMarkdown(text) }
    val style = LocalTextStyle.current

    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Paragraph -> Text(block.spans.annotated(), style = style, color = color)
                is Block.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = block.marker,
                        modifier = Modifier.widthIn(min = 14.dp),
                        style = style,
                        color = color.copy(alpha = 0.6f),
                    )
                    Text(block.spans.annotated(), style = style, color = color)
                }
            }
        }
    }
}

private fun List<Span>.annotated(): AnnotatedString = buildAnnotatedString {
    forEach { span ->
        val emphasis = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
        )
        if (span.bold || span.italic) {
            withStyle(emphasis) { append(span.text) }
        } else {
            append(span.text)
        }
    }
}
