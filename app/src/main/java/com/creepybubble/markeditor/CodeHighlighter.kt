package com.creepybubble.markeditor

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.compose.ui.graphics.toArgb

/**
 * Destaque de sintaxe leve e agnóstico de linguagem, sem dependências externas.
 * Pinta caractere a caractere por prioridade (comentário/string vencem palavra-chave/número),
 * evitando conflito de spans sobrepostos.
 */

private val codeKeywords = setOf(
    // controle de fluxo / declarações comuns a várias linguagens
    "fun", "val", "var", "if", "else", "when", "for", "while", "do", "return", "break", "continue",
    "class", "object", "interface", "enum", "struct", "trait", "impl", "extends", "implements",
    "import", "package", "from", "as", "use", "using", "namespace", "module",
    "private", "public", "protected", "internal", "static", "final", "abstract", "override", "open",
    "true", "false", "null", "nil", "none", "this", "self", "super", "new", "delete",
    "try", "catch", "finally", "throw", "throws", "raise", "except",
    "in", "is", "not", "and", "or", "def", "lambda", "async", "await", "yield", "with",
    "function", "const", "let", "void", "int", "long", "float", "double", "char", "bool", "boolean",
    "string", "str", "print", "println", "echo", "typeof", "instanceof"
)

private val keywordRegex =
    Regex("\\b(" + codeKeywords.joinToString("|") { Regex.escape(it) } + ")\\b")
private val numberRegex = Regex("\\b\\d+(\\.\\d+)?\\b")
private val stringRegex =
    Regex("\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'|`([^`\\\\]|\\\\.)*`")
private val commentRegex = Regex("//[^\\n]*|#[^\\n]*|/\\*[\\s\\S]*?\\*/")

fun highlightCode(code: String): CharSequence {
    val n = code.length
    val defaultColor = gruvboxText.toArgb()
    val colors = IntArray(n) { defaultColor }

    fun paint(regex: Regex, color: Int) {
        for (m in regex.findAll(code)) {
            for (i in m.range) if (i in 0 until n) colors[i] = color
        }
    }

    // Ordem = prioridade crescente (o último sobrescreve).
    paint(keywordRegex, gruvboxRed.toArgb())
    paint(numberRegex, gruvboxPurple.toArgb())
    paint(stringRegex, gruvboxAqua.toArgb())
    paint(commentRegex, gruvboxGray.toArgb())

    val spannable = SpannableString(code)
    var i = 0
    while (i < n) {
        var j = i
        while (j < n && colors[j] == colors[i]) j++
        if (colors[i] != defaultColor) {
            spannable.setSpan(ForegroundColorSpan(colors[i]), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        i = j
    }
    return spannable
}

/** Verifica se um bloco é um bloco de código cercado por ```. */
fun isCodeBlock(block: String): Boolean = block.trimStart().startsWith("```")

/** Extrai (linguagem, código) de um bloco cercado por ```. */
fun parseCodeBlock(block: String): Pair<String, String> {
    val lines = block.split("\n")
    val lang = lines.firstOrNull()?.trimStart()?.removePrefix("```")?.trim().orEmpty()
    val codeLines = lines.drop(1).toMutableList()
    if (codeLines.isNotEmpty() && codeLines.last().trimStart().startsWith("```")) {
        codeLines.removeAt(codeLines.size - 1)
    }
    return lang to codeLines.joinToString("\n")
}
