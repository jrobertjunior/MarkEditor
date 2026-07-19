package com.creepybubble.markeditor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.math.ceil

private val taskItemRegex = Regex("^(\\s*)([-*+])\\s+\\[[ xX]]\\s+(.*)$")
private val bulletItemRegex = Regex("^(\\s*)([-*+])\\s+(.*)$")
private val numberItemRegex = Regex("^(\\s*)(\\d+)([.)])\\s+(.*)$")

/**
 * Continua listas automaticamente quando o usuário aperta Enter.
 * - Item com conteúdo: cria o próximo marcador (numeração incrementada).
 * - Item vazio: remove o marcador (sai da lista).
 * Se não houver lista na linha anterior, devolve o valor sem mudanças.
 */
fun continueListOnNewline(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    // Só age quando exatamente um '\n' foi inserido na posição do cursor.
    if (new.text.length != old.text.length + 1) return new
    if (new.selection.start != new.selection.end) return new
    val cursor = new.selection.start
    if (cursor <= 0 || cursor > new.text.length) return new
    if (new.text[cursor - 1] != '\n') return new

    val before = new.text.substring(0, cursor - 1)
    val prevLineStart = before.lastIndexOf('\n') + 1
    val prevLine = before.substring(prevLineStart)

    val task = taskItemRegex.find(prevLine)
    val number = numberItemRegex.find(prevLine)
    val bullet = bulletItemRegex.find(prevLine)

    val marker: String
    val content: String
    when {
        task != null -> {
            marker = "${task.groupValues[1]}${task.groupValues[2]} [ ] "
            content = task.groupValues[3]
        }
        number != null -> {
            val n = number.groupValues[2].toIntOrNull() ?: 1
            marker = "${number.groupValues[1]}${n + 1}${number.groupValues[3]} "
            content = number.groupValues[4]
        }
        bullet != null -> {
            marker = "${bullet.groupValues[1]}${bullet.groupValues[2]} "
            content = bullet.groupValues[3]
        }
        else -> return new
    }

    // Item vazio: sai da lista removendo o marcador e o Enter.
    if (content.isBlank()) {
        val cut = new.text.substring(0, prevLineStart) + new.text.substring(cursor)
        return TextFieldValue(cut, TextRange(prevLineStart))
    }

    // Item com conteúdo: insere o próximo marcador logo após o Enter.
    val text = new.text.substring(0, cursor) + marker + new.text.substring(cursor)
    return TextFieldValue(text, TextRange(cursor + marker.length))
}

/** Regex de uma linha de tarefa: indentação, marcador, [ ]/[x] e conteúdo. */
val taskLineRegex = Regex("^(\\s*)([-*+])\\s+\\[([ xX])]\\s+(.*)$")

/** Um bloco é lista de tarefas se ao menos uma linha for um item de tarefa. */
fun isTaskListBlock(block: String): Boolean =
    block.split("\n").any { taskLineRegex.matches(it) }

/** Alterna [ ] <-> [x] na linha [lineIndex] de um bloco e devolve o bloco novo. */
fun toggleTaskLine(block: String, lineIndex: Int): String {
    val lines = block.split("\n").toMutableList()
    if (lineIndex !in lines.indices) return block
    val line = lines[lineIndex]
    lines[lineIndex] = if (Regex("\\[[xX]]").containsMatchIn(line)) {
        line.replaceFirst(Regex("\\[[xX]]"), "[ ]")
    } else {
        line.replaceFirst("[ ]", "[x]")
    }
    return lines.joinToString("\n")
}

/** Remove comentários HTML (<!-- ... -->) do markdown, para não renderizar/ler. */
fun removeComments(md: String): String =
    md.replace(Regex("(?s)<!--.*?-->"), "")

/** Conta palavras (sequências de caracteres não-espaço). */
fun countWords(text: String): Int =
    if (text.isBlank()) 0 else Regex("\\S+").findAll(text).count()

/** Tempo estimado de leitura em minutos (~200 palavras/min); no mínimo 1 se houver texto. */
fun readingMinutes(words: Int): Int =
    if (words <= 0) 0 else maxOf(1, ceil(words / 200.0).toInt())
