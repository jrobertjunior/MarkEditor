package com.creepybubble.markeditor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.text.input.TextFieldValue
import java.util.UUID

/**
 * Uma aba do editor. Pode ser um arquivo único (SingleTab) ou um projeto
 * (vários arquivos que são apresentados como um documento só, mas continuam
 * distintos no sistema de arquivos).
 */
sealed interface Tab {
    val id: UUID
    /** Rótulo exibido na barra de abas. */
    val title: String
    /** Se há mudanças não salvas em qualquer conteúdo desta aba. */
    val dirty: Boolean
    /** O Document atualmente editável desta aba (arquivo único ou parte ativa do projeto). */
    val editable: Document
}

/** Aba de arquivo único: comportamento clássico do editor. */
class SingleTab(val doc: Document) : Tab {
    override val id: UUID get() = doc.id
    override val title: String get() = doc.name
    override val dirty: Boolean get() = doc.isDirty
    override val editable: Document get() = doc
}

/**
 * Projeto: lista ordenada de arquivos (Document) tratada como um documento só.
 * - No editor, edita-se uma parte por vez (a parte ativa).
 * - No visualizador, o texto de todas as partes é concatenado e lido como um só.
 */
class Project(
    override val id: UUID = UUID.randomUUID(),
    name: String = "Projeto",
    parts: List<Document> = emptyList(),
) : Tab {
    var name: String by mutableStateOf(name)
    /** Partes na ordem em que aparecem no documento combinado. */
    val parts = parts.toMutableStateList()
    /** Índice da parte sendo editada no momento. */
    var activeIndex: Int by mutableStateOf(0)

    /** Posição de rolagem do visualizador combinado (para restaurar onde estava). */
    var previewScrollIndex: Int by mutableStateOf(0)
    var previewScrollOffset: Int by mutableStateOf(0)

    override val title: String get() = name
    override val dirty: Boolean get() = parts.any { it.isDirty }
    override val editable: Document
        get() = parts[activeIndex.coerceIn(0, (parts.size - 1).coerceAtLeast(0))]

    /** Texto de todas as partes concatenado (o que o visualizador mostra). */
    fun combinedText(): String = parts.joinToString(SEP) { it.textState.text }

    /**
     * Intervalos [início, fimExclusivo) de cada parte dentro do texto combinado.
     * Usado para mapear posições do preview de volta para a parte correta.
     */
    fun partRanges(): List<IntRange> {
        val res = ArrayList<IntRange>(parts.size)
        var pos = 0
        parts.forEachIndexed { i, d ->
            val len = d.textState.text.length
            res.add(pos until (pos + len))
            pos += len + if (i < parts.size - 1) SEP.length else 0
        }
        return res
    }

    /** Offset (no texto combinado) onde a parte informada começa. */
    fun partStartOffset(index: Int): Int {
        var pos = 0
        for (i in 0 until index.coerceIn(0, parts.size)) {
            pos += parts[i].textState.text.length + SEP.length
        }
        return pos
    }

    /**
     * Aplica uma edição feita no texto combinado (ex.: marcar um checkbox no preview)
     * de volta na parte correta. Só age quando a mudança está contida em uma única
     * parte — o que cobre os casos reais do preview (toggle de tarefa). Se a alteração
     * cruzar a fronteira entre partes, é ignorada para não corromper os arquivos.
     */
    fun applyCombinedEdit(newCombined: String) {
        val old = combinedText()
        if (newCombined == old) return
        // Prefixo comum
        var p = 0
        val minLen = minOf(old.length, newCombined.length)
        while (p < minLen && old[p] == newCombined[p]) p++
        // Sufixo comum (sem invadir o prefixo)
        var s = 0
        while (s < (minLen - p) && old[old.length - 1 - s] == newCombined[newCombined.length - 1 - s]) s++
        val changeStart = p
        val oldEnd = old.length - s
        // Descobre em qual parte cai a alteração
        val ranges = partRanges()
        val idx = ranges.indexOfFirst { changeStart >= it.first && oldEnd <= it.last + 1 }
        if (idx < 0) return
        val range = ranges[idx]
        val localStart = changeStart - range.first
        val localEnd = oldEnd - range.first
        val fragment = newCombined.substring(p, newCombined.length - s)
        val partText = parts[idx].textState.text
        if (localStart < 0 || localEnd > partText.length || localStart > localEnd) return
        val updated = partText.replaceRange(localStart, localEnd, fragment)
        parts[idx].textState = TextFieldValue(updated, androidx.compose.ui.text.TextRange(localStart + fragment.length))
    }

    companion object {
        /** Separador entre partes no texto combinado (linha em branco). */
        const val SEP = "\n\n"
    }
}
