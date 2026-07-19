package com.creepybubble.markeditor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration

data class TocItem(val label: String, val level: Int, val index: Int)

/**
 * Quebra o markdown em blocos editáveis, usando linhas em branco como separador.
 * Blocos de código cercados por ``` são mantidos inteiros, mesmo com linhas vazias dentro.
 */
fun splitIntoBlocks(text: String): List<String> {
    val lines = text.split("\n")
    val blocks = mutableListOf<String>()
    val current = StringBuilder()
    var inFence = false

    fun flush() {
        if (current.isNotEmpty()) {
            blocks.add(current.toString().trimEnd('\n'))
            current.setLength(0)
        }
    }

    for (line in lines) {
        if (line.trimStart().startsWith("```")) {
            inFence = !inFence
            current.append(line).append('\n')
            continue
        }
        if (!inFence && line.isBlank()) {
            flush()
        } else {
            current.append(line).append('\n')
        }
    }
    flush()
    return if (blocks.isEmpty()) listOf("") else blocks
}

fun extractToc(text: String): List<TocItem> {
    val regex = Regex("^(#{1,6})\\s+(.*)$", RegexOption.MULTILINE)
    return regex.findAll(text).map { match ->
        TocItem(
            label = match.groupValues[2],
            level = match.groupValues[1].length,
            index = match.range.first
        )
    }.toList()
}

class MarkdownGruvboxTransformation : VisualTransformation {
    companion object {
        private val headerRegex = Regex("^(#{1,6}) (.*)", RegexOption.MULTILINE)
        private val boldRegex = Regex("\\*\\*(.*?)\\*\\*")
        private val italicRegex = Regex("(?<!\\*)\\*(?!\\*)(.*?)(?<!\\*)\\*(?!\\*)")
        private val codeRegex = Regex("`(.*?)`")
        private val quoteRegex = Regex("^> (.*)", RegexOption.MULTILINE)
        private val linkRegex = Regex("\\[(.*?)\\]\\((.*?)\\)")
    }

    override fun filter(text: AnnotatedString): TransformedText {
        val builder = AnnotatedString.Builder(text.text)
        val stringText = text.text

        headerRegex.findAll(stringText).forEach { match ->
            builder.addStyle(SpanStyle(color = gruvboxRed, fontWeight = FontWeight.Bold), match.range.first, match.range.last + 1)
        }
        boldRegex.findAll(stringText).forEach { match ->
            builder.addStyle(SpanStyle(color = gruvboxOrange, fontWeight = FontWeight.Bold), match.range.first, match.range.last + 1)
        }
        italicRegex.findAll(stringText).forEach { match ->
            builder.addStyle(SpanStyle(color = gruvboxYellow, fontStyle = FontStyle.Italic), match.range.first, match.range.last + 1)
        }
        codeRegex.findAll(stringText).forEach { match ->
            builder.addStyle(SpanStyle(color = gruvboxAqua, background = gruvboxSurface, fontFamily = FontFamily.Monospace), match.range.first, match.range.last + 1)
        }
        quoteRegex.findAll(stringText).forEach { match ->
            builder.addStyle(SpanStyle(color = gruvboxGray, fontStyle = FontStyle.Italic), match.range.first, match.range.last + 1)
        }
        linkRegex.findAll(stringText).forEach { match ->
            builder.addStyle(SpanStyle(color = gruvboxBlue, textDecoration = TextDecoration.Underline), match.range.first, match.range.last + 1)
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}