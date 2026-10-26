package com.tunespark.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A lightweight, GitHub-style Markdown renderer for GitHub release notes.
 *
 * Supported syntax:
 *  - Headings: # / ## / ### / #### (sizes scale with screen width)
 *  - Bold **text**, italic *text*, bold-italic ***text***
 *  - Inline code `code` and fenced code blocks ``` ```
 *  - Bullet lists (- / *) and numbered lists (1.)
 *  - Nested lists (2-space indentation, one level)
 *  - Blockquotes (> text)
 *  - Horizontal rules (--- / ***)
 *  - Links [text](url) — clickable, opens in the browser
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onBackground
) {
    val configuration = LocalConfiguration.current
    // Scale type sizes with screen width: 360dp is the reference width.
    val fontScale = remember(configuration.screenWidthDp) {
        (configuration.screenWidthDp / 360f).coerceIn(0.85f, 1.25f)
    }

    val blocks = remember(markdown) { parseMarkdownBlocks(markdown.trim()) }

    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> MarkdownInlineText(
                    text = block.text,
                    fontSize = (block.levelToSp() * fontScale).sp,
                    fontWeight = FontWeight.Bold,
                    textColor = textColor,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                )
                is MdBlock.Paragraph -> MarkdownInlineText(
                    text = block.text,
                    fontSize = (14 * fontScale).sp,
                    textColor = textColor.copy(alpha = 0.85f),
                    lineHeight = (20 * fontScale).sp,
                    modifier = Modifier.padding(vertical = 3.dp)
                )
                is MdBlock.ListItem -> Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        text = if (block.ordered) "${block.number}.  " else "•  ",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = (14 * fontScale).sp,
                        fontWeight = if (block.ordered) FontWeight.SemiBold else FontWeight.Bold,
                        modifier = Modifier.padding(start = (if (block.indent) 16 else 2).dp)
                    )
                    MarkdownInlineText(
                        text = block.text,
                        fontSize = (14 * fontScale).sp,
                        textColor = textColor.copy(alpha = 0.85f),
                        lineHeight = (20 * fontScale).sp
                    )
                }
                is MdBlock.Quote -> Row(modifier = Modifier.padding(vertical = 4.dp)) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .heightIn(min = 18.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                    )
                    MarkdownInlineText(
                        text = block.text,
                        fontSize = (14 * fontScale).sp,
                        textColor = textColor.copy(alpha = 0.7f),
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
                is MdBlock.CodeBlock -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(textColor.copy(alpha = 0.06f))
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    Text(
                        text = block.text,
                        color = textColor.copy(alpha = 0.9f),
                        fontSize = (12.5 * fontScale).sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = (17 * fontScale).sp
                    )
                }
                MdBlock.Rule -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .height(1.dp)
                        .background(textColor.copy(alpha = 0.15f))
                )
            }
        }
    }
}

private fun MdBlock.Heading.levelToSp(): Int = when (level) {
    1 -> 21
    2 -> 18
    3 -> 16
    else -> 15
}

private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class ListItem(val text: String, val ordered: Boolean, val number: Int = 0, val indent: Boolean = false) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class CodeBlock(val text: String) : MdBlock()
    object Rule : MdBlock()
}

private fun parseMarkdownBlocks(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = source.lines()
    var i = 0
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) {
            blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
        }
        paragraph.clear()
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        when {
            // Fenced code block
            trimmed.startsWith("```") -> {
                flushParagraph()
                i++
                val code = StringBuilder()
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.appendLine(lines[i])
                    i++
                }
                i++ // skip closing fence
                blocks.add(MdBlock.CodeBlock(code.toString().trimEnd()))
            }
            // Heading
            trimmed.matches(Regex("#{1,6}\\s+.+")) -> {
                flushParagraph()
                val level = trimmed.takeWhile { it == '#' }.length
                blocks.add(MdBlock.Heading(level, trimmed.dropWhile { it == '#' }.trim()))
                i++
            }
            // Horizontal rule
            trimmed.matches(Regex("(-{3,}|\\*{3,}|_{3,})")) -> {
                flushParagraph()
                blocks.add(MdBlock.Rule)
                i++
            }
            // Blockquote
            trimmed.startsWith(">") -> {
                flushParagraph()
                val quote = StringBuilder(trimmed.removePrefix(">").trim())
                i++
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quote.append(" ").append(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                blocks.add(MdBlock.Quote(quote.toString()))
            }
            // Bullet list
            Regex("^([-*+])\\s+(.*)").matches(trimmed) -> {
                flushParagraph()
                val indent = line.takeWhile { it == ' ' }.length >= 2
                blocks.add(MdBlock.ListItem(Regex("^([-*+])\\s+(.*)").find(trimmed)!!.groupValues[2], ordered = false, indent = indent))
                i++
            }
            // Ordered list
            Regex("^(\\d+)[.)]\\s+(.*)").matches(trimmed) -> {
                flushParagraph()
                val match = Regex("^(\\d+)[.)]\\s+(.*)").find(trimmed)!!
                val indent = line.takeWhile { it == ' ' }.length >= 2
                blocks.add(MdBlock.ListItem(match.groupValues[2], ordered = true, number = match.groupValues[1].toIntOrNull() ?: 0, indent = indent))
                i++
            }
            // Blank line
            trimmed.isEmpty() -> {
                flushParagraph()
                i++
            }
            // Paragraph text (merge consecutive lines)
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(trimmed)
                i++
            }
        }
    }
    flushParagraph()
    return blocks
}

/** Renders inline markdown (bold / italic / code / links) into a tappable Text. */
@Composable
private fun MarkdownInlineText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    textColor: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    lineHeight: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified
) {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) { parseInlineMarkdown(text, linkColor) }
    val layoutResult = remember { androidx.compose.runtime.mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text = annotated,
        color = textColor,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        onTextLayout = { layoutResult.value = it },
        modifier = modifier.pointerInput(annotated) {
            detectTapGestures { position ->
                layoutResult.value?.let { layout ->
                    val offset = layout.getOffsetForPosition(position)
                    annotated.getStringAnnotations("URL", offset, offset)
                        .firstOrNull()
                        ?.let { runCatching { uriHandler.openUri(it.item) } }
                }
            }
        }
    )
}

private fun parseInlineMarkdown(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    // Tokenizer: inline code, bold-italic, bold, italic, links
    val pattern = Regex(
        "(`[^`]+`)" +                            // code
            "|(\\*\\*\\*([^*]+?)\\*\\*\\*)" +    // bold-italic
            "|(\\*\\*([^*]+?)\\*\\*)" +          // bold
            "|(\\*([^*]+?)\\*)" +                // italic
            "|(\\[([^\\]]+)\\]\\(([^)\\s]+)\\))" // link
    )
    var lastIndex = 0
    for (match in pattern.findAll(text)) {
        append(text.substring(lastIndex, match.range.first))
        val full = match.value
        when {
            full.startsWith("`") -> {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = linkColor.copy(alpha = 0.10f), color = linkColor))
                append(full.trim('`'))
                pop()
            }
            full.startsWith("***") -> appendWith(parseInlineMarkdown(match.groupValues[3], linkColor), bold = true, italic = true)
            full.startsWith("**") -> appendWith(parseInlineMarkdown(match.groupValues[5], linkColor), bold = true)
            full.startsWith("*") -> appendWith(parseInlineMarkdown(match.groupValues[7], linkColor), italic = true)
            full.startsWith("[") -> {
                pushStringAnnotation("URL", match.groupValues[10])
                pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium))
                append(match.groupValues[9])
                pop(); pop()
            }
        }
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) append(text.substring(lastIndex))
}

private fun AnnotatedString.Builder.appendWith(inner: AnnotatedString, bold: Boolean = false, italic: Boolean = false) {
    pushStyle(
        SpanStyle(
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) FontStyle.Italic else null
        )
    )
    append(inner)
    pop()
}

