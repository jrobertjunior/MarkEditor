package com.creepybubble.markeditor

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.util.UUID

class Document(
    val id: UUID = UUID.randomUUID(),
    var name: String = "Novo Arquivo.md",
    var uri: Uri? = null,
    initialText: String = "# Novo Arquivo\n\nComece a digitar...",
    initialSelection: TextRange? = null,
    initialScrollIndex: Int = 0,
    initialScrollOffset: Int = 0
) {
    // Cada aba gerencia o próprio estado e o próprio histórico de Ctrl+Z
    var textState by mutableStateOf(
        if (initialSelection != null) TextFieldValue(initialText, initialSelection)
        else TextFieldValue(initialText)
    )
    val undoRedoManager = UndoRedoManager(textState)

    // Texto da última gravação/abertura; se difere do atual, há mudanças não salvas.
    var savedText by mutableStateOf(initialText)
    val isDirty: Boolean get() = textState.text != savedText

    // Posição de rolagem do modo de exibição (para restaurar onde o usuário estava).
    var previewScrollIndex: Int = initialScrollIndex
    var previewScrollOffset: Int = initialScrollOffset
}