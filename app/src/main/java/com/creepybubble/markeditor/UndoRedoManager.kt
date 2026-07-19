package com.creepybubble.markeditor

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.text.input.TextFieldValue

class UndoRedoManager(initialValue: TextFieldValue) {
    private val undoStack = mutableStateListOf(initialValue)
    private val redoStack = mutableStateListOf<TextFieldValue>()
    private var isInternalChange = false

    val canUndo: Boolean get() = undoStack.size > 1
    val canRedo: Boolean get() = redoStack.size > 0

    fun record(value: TextFieldValue) {
        if (isInternalChange) return

        val lastState = undoStack.lastOrNull()
        val lastText = lastState?.text ?: ""
        val newText = value.text

        if (lastText == newText) return // Ignora se não houve mudança real

        val lengthDiff = Math.abs(lastText.length - newText.length)

        // Detecta se a última letra digitada foi um delimitador de palavra
        val isWordBoundary = newText.length > lastText.length &&
                (newText.lastOrNull()?.isWhitespace() == true ||
                        newText.lastOrNull() in listOf('.', ',', ';', '!', '?', ':'))

        // Salva um estado NOVO na pilha se:
        // 1. Mudou mais de 1 caractere de uma vez (colou texto ou clicou em um atalho de tag)
        // 2. O usuário terminou de digitar uma palavra (espaço, enter ou pontuação)
        if (lengthDiff > 1 || isWordBoundary) {
            undoStack.add(value)
            redoStack.clear()
            // Limita o histórico a 200 passos para o app não engolir a memória do celular
            if (undoStack.size > 200) undoStack.removeAt(0)
        } else {
            // Se está apenas digitando letras no meio de uma palavra, atualiza o último estado.
            // Assim, um Ctrl+Z apaga a palavra atual que estava sendo construída, e não o texto todo.
            if (undoStack.isNotEmpty()) {
                undoStack[undoStack.size - 1] = value
            }
        }
    }

    fun undo(updateState: (TextFieldValue) -> Unit) {
        if (canUndo) {
            isInternalChange = true
            redoStack.add(undoStack.removeLast())
            updateState(undoStack.last())
            isInternalChange = false
        }
    }

    fun redo(updateState: (TextFieldValue) -> Unit) {
        if (canRedo) {
            isInternalChange = true
            val value = redoStack.removeLast()
            undoStack.add(value)
            updateState(value)
            isInternalChange = false
        }
    }
}