package com.creepybubble.markeditor

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin

/** Faixa da palavra sendo lida dentro do texto falado de um bloco. */
private data class WordHighlight(val spoken: String, val start: Int, val end: Int)

// Laranja translúcido para o fundo da palavra que está sendo lida.
private const val READING_WORD_BG: Int = 0x66D65D0E

/**
 * Visualizador editável. Um toque num bloco o seleciona (ponto de partida da leitura);
 * um segundo toque no bloco já selecionado, ou um toque longo, abre a edição no local.
 */
@Composable
fun MarkdownPreview(
    text: String,
    onTextChange: (String) -> Unit,
    jumpToIndex: Int?,
    onJumpConsumed: () -> Unit,
    initialScrollIndex: Int = 0,
    initialScrollOffset: Int = 0,
    onScrollChanged: (Int, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val markwon = remember {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .build()
    }

    val blocks = remember { mutableStateListOf<String>().apply { addAll(splitIntoBlocks(text)) } }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var selectedBlockIndex by remember { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState(initialScrollIndex, initialScrollOffset)
    val tts = rememberTtsManager()
    var showTtsSettings by remember { mutableStateOf(false) }

    if (showTtsSettings) {
        TtsSettingsDialog(tts = tts, onDismiss = { showTtsSettings = false })
    }

    // Re-sincroniza quando o texto muda por fora (abrir arquivo, undo/redo, etc.)
    LaunchedEffect(text) {
        if (blocks.joinToString("\n\n") != text) {
            blocks.clear()
            blocks.addAll(splitIntoBlocks(text))
            editingIndex = null
            selectedBlockIndex = null
        }
    }

    // Pulo pelo índice (TOC): encontra o bloco que contém o offset e rola até ele.
    LaunchedEffect(jumpToIndex) {
        val target = jumpToIndex ?: return@LaunchedEffect
        var acc = 0
        var idx = 0
        for (i in blocks.indices) {
            val len = blocks[i].length
            if (target <= acc + len) { idx = i; break }
            acc += len + 2 // "\n\n" entre blocos
            idx = i
        }
        listState.animateScrollToItem(idx)
        onJumpConsumed()
    }

    // Enquanto lê, rola sozinho até o bloco atual e fecha qualquer edição aberta.
    LaunchedEffect(tts.currentIndex) {
        val i = tts.currentIndex
        if (i in blocks.indices) {
            editingIndex = null
            listState.animateScrollToItem(i)
        }
    }

    // Reporta a rolagem para persistência.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (i, o) -> onScrollChanged(i, o) }
    }

    Column(modifier = modifier) {
        TtsControlBar(
            isSpeaking = tts.isSpeaking,
            isPaused = tts.isPaused,
            onPlayPause = {
                val start = selectedBlockIndex
                when {
                    tts.isSpeaking -> tts.pause()
                    // Uma seção selecionada tem prioridade: começa a leitura ali e "consome" a seleção.
                    start != null -> {
                        selectedBlockIndex = null
                        tts.speak(blocks.toList(), start)
                    }
                    tts.isPaused -> tts.resume()
                    else -> tts.speak(blocks.toList(), 0)
                }
            },
            onPrevious = { tts.previous() },
            onNext = { tts.next() },
            onStop = { tts.stop() },
            onOpenSettings = { showTtsSettings = true }
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            itemsIndexed(blocks) { index, block ->
                if (editingIndex == index) {
                    BlockEditField(
                        initial = block,
                        onCommit = { newText ->
                            if (index < blocks.size) blocks[index] = newText
                            onTextChange(blocks.joinToString("\n\n"))
                            if (editingIndex == index) editingIndex = null
                        }
                    )
                } else {
                    val highlight = if (index == tts.currentIndex && tts.currentWordStart >= 0) {
                        WordHighlight(tts.spokenText(index), tts.currentWordStart, tts.currentWordEnd)
                    } else {
                        null
                    }
                    RenderedBlock(
                        markwon = markwon,
                        source = block,
                        isReading = index == tts.currentIndex,
                        isSelected = index == selectedBlockIndex,
                        highlight = highlight,
                        onClick = {
                            // 1º toque seleciona; 2º toque no já selecionado abre a edição.
                            if (selectedBlockIndex == index) {
                                editingIndex = index
                            } else {
                                selectedBlockIndex = index
                            }
                        },
                        onLongClick = { editingIndex = index }
                    )
                }
            }
        }
    }
}

@Composable
private fun TtsControlBar(
    isSpeaking: Boolean,
    isPaused: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val active = isSpeaking || isPaused
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(gruvboxSurface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TtsButton(
            icon = Icons.Default.SkipPrevious,
            description = "Seção anterior",
            enabled = active,
            onClick = onPrevious
        )
        TtsButton(
            icon = if (isSpeaking) Icons.Default.Pause else Icons.Default.PlayArrow,
            description = when {
                isSpeaking -> "Pausar"
                isPaused -> "Retomar"
                else -> "Ler em voz alta"
            },
            tint = if (active) gruvboxOrange else gruvboxText,
            onClick = onPlayPause
        )
        TtsButton(
            icon = Icons.Default.SkipNext,
            description = "Próxima seção",
            enabled = active,
            onClick = onNext
        )
        TtsButton(
            icon = Icons.Default.Stop,
            description = "Parar leitura",
            enabled = active,
            tint = if (active) gruvboxRed else gruvboxGray,
            onClick = onStop
        )

        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = when {
                isSpeaking -> "Lendo…"
                isPaused -> "Pausado"
                else -> "Ler em voz alta"
            },
            color = if (active) gruvboxOrange else gruvboxGray,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )

        TtsButton(
            icon = Icons.Default.Tune,
            description = "Escolher voz e motor",
            onClick = onOpenSettings
        )
    }
}

@Composable
private fun TtsButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    tint: Color = gruvboxText,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(40.dp)
            .background(Color(if (enabled) 0xFF504945 else 0xFF3C3836), RoundedCornerShape(8.dp))
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) tint else gruvboxGray
        )
    }
}

@Composable
private fun RenderedBlock(
    markwon: Markwon,
    source: String,
    isReading: Boolean,
    isSelected: Boolean,
    highlight: WordHighlight?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    // Cores por estado: leitura = laranja, seleção = azul, edição (outro composable) = lilás.
    val borderColor: Color? = when {
        isReading -> gruvboxOrange
        isSelected -> gruvboxBlue
        else -> null
    }
    var boxModifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 2.dp)
    if (borderColor != null) {
        boxModifier = boxModifier.border(1.5.dp, borderColor, RoundedCornerShape(8.dp))
    }

    AndroidView(
        modifier = boxModifier,
        factory = { ctx ->
            TextView(ctx).apply {
                textSize = 16f
                setTextColor(gruvboxText.toArgb())
                setBackgroundColor(gruvboxSurface.toArgb())
                setPadding(16, 8, 16, 8)
                isClickable = true
                isFocusable = false
                isLongClickable = true
            }
        },
        update = { tv ->
            markwon.setMarkdown(tv, source)
            applyWordHighlight(tv, highlight)
            tv.setOnClickListener { onClick() }
            tv.setOnLongClickListener { onLongClick(); true }
        }
    )
}

/**
 * Destaca (fundo translúcido) a palavra que está sendo lida. Como o texto renderizado
 * pelo Markwon difere do texto falado, procuramos a palavra por aproximação de posição.
 */
private fun applyWordHighlight(tv: TextView, highlight: WordHighlight?) {
    if (highlight == null) return
    val spoken = highlight.spoken
    if (highlight.start < 0 || highlight.end <= highlight.start || highlight.end > spoken.length) return

    val word = spoken.substring(highlight.start, highlight.end).trim()
    if (word.isBlank()) return

    val rendered = tv.text.toString()
    val approx = if (spoken.isNotEmpty()) {
        (rendered.length.toLong() * highlight.start / spoken.length).toInt()
    } else {
        0
    }
    var idx = rendered.indexOf(word, (approx - word.length - 8).coerceAtLeast(0))
    if (idx < 0) idx = rendered.indexOf(word)
    if (idx < 0) return

    val spannable = SpannableString(tv.text)
    spannable.setSpan(
        BackgroundColorSpan(READING_WORD_BG),
        idx,
        (idx + word.length).coerceAtMost(spannable.length),
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    tv.text = spannable
}

@Composable
private fun BlockEditField(initial: String, onCommit: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focusRequester = remember { FocusRequester() }
    var hasFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .border(1.5.dp, gruvboxLilac, RoundedCornerShape(8.dp))
    ) {
        TextField(
            value = value,
            onValueChange = { value = it },
            visualTransformation = MarkdownGruvboxTransformation(),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 16.sp),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        hasFocused = true
                    } else if (hasFocused) {
                        onCommit(value.text)
                    }
                },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = gruvboxBg, unfocusedContainerColor = gruvboxBg,
                focusedTextColor = gruvboxText, unfocusedTextColor = gruvboxText,
                cursorColor = gruvboxText,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
            )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onCommit(value.text) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Check, contentDescription = "Concluir edição", tint = gruvboxLilac)
            }
        }
    }
}
