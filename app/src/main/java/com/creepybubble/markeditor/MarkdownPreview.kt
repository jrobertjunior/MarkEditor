package com.creepybubble.markeditor

import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.widget.TextView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.network.NetworkSchemeHandler
import kotlinx.coroutines.delay

/** Encontra o índice do bloco que contém um dado offset de caractere. */
private fun blockIndexForOffset(blocks: List<String>, offset: Int): Int {
    var acc = 0
    for (i in blocks.indices) {
        val len = blocks[i].length
        if (offset <= acc + len) return i
        acc += len + 2 // "\n\n" entre blocos
    }
    return (blocks.size - 1).coerceAtLeast(0)
}

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
    startReadingFromOffset: Int? = null,
    onStartReadingConsumed: () -> Unit = {},
    fontSize: Float = 16f,
    documentTitle: String = "Documento",
    // Sincronização de rolagem (modo lado a lado): recebe uma fração 0..1 para rolar até,
    // e reporta a própria fração conforme o usuário rola (com flag de "rolando agora").
    externalScrollFraction: Float? = null,
    onScrollFraction: (Float, Boolean) -> Unit = { _, _ -> },
    // Ao vir da edição: seleciona (borda azul) e rola até o bloco desse offset.
    selectBlockOffset: Int? = null,
    onSelectConsumed: () -> Unit = {},
    // Reporta o bloco "em foco" (selecionado > em leitura > topo visível) para o app.
    onActiveBlockChanged: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val markwon = remember(fontSize) {
        val latexPx = with(density) { fontSize.sp.toPx() }
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(JLatexMathPlugin.create(latexPx))
            // Imagens: web (http/https), arquivo, data URI e content:// (galeria/arquivos).
            .usePlugin(ImagesPlugin.create { plugin ->
                plugin.addSchemeHandler(NetworkSchemeHandler.create())
                plugin.addSchemeHandler(ContentSchemeHandler(context))
            })
            .build()
    }

    val blocks = remember { mutableStateListOf<String>().apply { addAll(splitIntoBlocks(text)) } }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var selectedBlockIndex by remember { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState(initialScrollIndex, initialScrollOffset)
    val tts = rememberTtsManager()
    var showTtsSettings by remember { mutableStateOf(false) }

    // Inicia o serviço em foreground para permitir leitura em segundo plano + notificação.
    val startTtsService = {
        tts.currentTitle = documentTitle
        context.startForegroundService(Intent(context, TtsService::class.java))
    }

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

    // Ao voltar da edição: seleciona (borda azul) o bloco onde o cursor estava e rola até ele.
    LaunchedEffect(selectBlockOffset) {
        val target = selectBlockOffset ?: return@LaunchedEffect
        var acc = 0
        var idx = 0
        for (i in blocks.indices) {
            val len = blocks[i].length
            if (target <= acc + len) { idx = i; break }
            acc += len + 2
            idx = i
        }
        editingIndex = null
        selectedBlockIndex = idx
        listState.animateScrollToItem(idx)
        onSelectConsumed()
    }

    // Reporta o bloco em foco: selecionado, senão em leitura, senão o do topo.
    LaunchedEffect(Unit) {
        snapshotFlow { selectedBlockIndex ?: tts.currentIndex.takeIf { it >= 0 } ?: listState.firstVisibleItemIndex }
            .collect { onActiveBlockChanged(it) }
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

    // Sincronização (lado a lado): rola até a fração pedida pelo editor.
    LaunchedEffect(externalScrollFraction) {
        val f = externalScrollFraction ?: return@LaunchedEffect
        if (blocks.isNotEmpty() && !listState.isScrollInProgress) {
            val pos = f * blocks.size
            val idx = pos.toInt().coerceIn(0, blocks.size - 1)
            listState.scrollToItem(idx)
        }
    }

    // Sincronização (lado a lado): reporta a própria fração de rolagem.
    LaunchedEffect(listState) {
        snapshotFlow {
            val total = blocks.size
            val frac = if (total <= 0) 0f else {
                val idx = listState.firstVisibleItemIndex
                val itemH = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx }?.size ?: 0
                val intra = if (itemH > 0) listState.firstVisibleItemScrollOffset.toFloat() / itemH else 0f
                ((idx + intra) / total).coerceIn(0f, 1f)
            }
            frac to listState.isScrollInProgress
        }.collect { (frac, inProg) -> onScrollFraction(frac, inProg) }
    }

    // "Ler a partir do cursor": rola até o bloco e começa a leitura ali.
    LaunchedEffect(startReadingFromOffset) {
        val offset = startReadingFromOffset ?: return@LaunchedEffect
        val idx = blockIndexForOffset(blocks, offset)
        selectedBlockIndex = idx // fallback caso o motor ainda não esteja pronto
        listState.animateScrollToItem(idx)
        delay(350) // dá tempo para o motor de TTS iniciar
        startTtsService()
        tts.speak(blocks.toList(), idx)
        onStartReadingConsumed()
    }

    // Contagem de palavras acumulada por bloco, para o progresso de leitura.
    // Derivada direto do texto para nunca ficar defasada após edições.
    val wordPrefix = remember(text) {
        val bl = splitIntoBlocks(text)
        val arr = IntArray(bl.size + 1)
        for (i in bl.indices) arr[i + 1] = arr[i] + countWords(bl[i])
        arr
    }
    val totalWords = wordPrefix.lastOrNull() ?: 0
    // Detecta se o fim do conteúdo já está inteiramente na tela (não há mais o que rolar).
    // Sem isso, o progresso fica preso no "início" do último bloco visível no topo.
    val atEnd = run {
        val li = listState.layoutInfo
        val last = li.visibleItemsInfo.lastOrNull()
        last != null && last.index == li.totalItemsCount - 1 &&
            last.offset + last.size <= li.viewportEndOffset - li.afterContentPadding
    }
    // Palavras "já passadas": pelo TTS quando lendo, senão pela posição do scroll (contínua).
    val positionWords: Float = if (tts.currentIndex >= 0) {
        wordPrefix.getOrElse(tts.currentIndex.coerceIn(0, wordPrefix.size - 1)) { 0 }.toFloat()
    } else if (atEnd) {
        // Chegou ao fim: 100%, independente de qual bloco está no topo.
        totalWords.toFloat()
    } else {
        val first = listState.firstVisibleItemIndex.coerceIn(0, wordPrefix.size - 1)
        val base = wordPrefix.getOrElse(first) { 0 }
        val next = wordPrefix.getOrElse(first + 1) { base }
        // Quanto já rolamos dentro do bloco visível (0..1), para um progresso suave.
        val itemSize = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == first }?.size ?: 0
        val intra = if (itemSize > 0) {
            (listState.firstVisibleItemScrollOffset.toFloat() / itemSize).coerceIn(0f, 1f)
        } else 0f
        base + intra * (next - base)
    }
    val fraction = if (totalWords > 0) (positionWords / totalWords).coerceIn(0f, 1f) else 0f
    val wordsLeft = (totalWords - positionWords).toInt().coerceAtLeast(0)

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
                        startTtsService()
                        tts.speak(blocks.toList(), start)
                    }
                    tts.isPaused -> {
                        startTtsService()
                        tts.resume()
                    }
                    else -> {
                        startTtsService()
                        tts.speak(blocks.toList(), 0)
                    }
                }
            },
            onPrevious = { tts.previous() },
            onNext = { tts.next() },
            onStop = { tts.stop() },
            onOpenSettings = { showTtsSettings = true }
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp)) {
            itemsIndexed(blocks) { index, block ->
                // 1º toque seleciona; 2º toque no já selecionado abre a edição.
                val onClick = {
                    if (selectedBlockIndex == index) editingIndex = index
                    else selectedBlockIndex = index
                }
                val onLongClick = { editingIndex = index }

                when {
                    editingIndex == index -> {
                        // Tabela edita como grade (WYSIWYG); demais blocos editam como texto.
                        if (isTableBlock(block)) TableBlock(
                            source = block,
                            fontSize = fontSize,
                            onChange = { updated ->
                                if (index < blocks.size) blocks[index] = updated
                                onTextChange(blocks.joinToString("\n\n"))
                            },
                            onDone = { if (editingIndex == index) editingIndex = null }
                        ) else BlockEditField(
                            initial = block,
                            fontSize = fontSize,
                            onCommit = { newText ->
                                if (index < blocks.size) blocks[index] = newText
                                onTextChange(blocks.joinToString("\n\n"))
                                if (editingIndex == index) editingIndex = null
                            }
                        )
                    }

                    isMermaidBlock(block) -> MermaidBlock(
                        source = block,
                        isSelected = index == selectedBlockIndex,
                        onClick = onClick
                    )

                    isCodeBlock(block) -> CodeBlock(
                        source = block,
                        isSelected = index == selectedBlockIndex,
                        fontSize = fontSize,
                        onClick = onClick,
                        onLongClick = onLongClick
                    )

                    isTaskListBlock(block) -> TaskListBlock(
                        source = block,
                        isSelected = index == selectedBlockIndex,
                        fontSize = fontSize,
                        onToggle = { lineIndex ->
                            val updated = toggleTaskLine(block, lineIndex)
                            if (index < blocks.size) blocks[index] = updated
                            onTextChange(blocks.joinToString("\n\n"))
                        },
                        onClick = onClick
                    )

                    else -> {
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
                            fontSize = fontSize,
                            onClick = onClick,
                            onLongClick = onLongClick
                        )
                    }
                }
            }
        }
        ReadingProgressBar(fraction = fraction, wordsLeft = wordsLeft, totalWords = totalWords)
    }
}

@Composable
private fun ReadingProgressBar(fraction: Float, wordsLeft: Int, totalWords: Int) {
    val percent = (fraction * 100).toInt().coerceIn(0, 100)
    val minutesLeft = readingMinutes(wordsLeft)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(gruvboxSurface)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // Trilha fina de progresso.
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(gruvboxBg)
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(gruvboxOrange)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.percent_read, percent), color = gruvboxGray, fontSize = 12.sp)
            Text(
                text = if (totalWords <= 0) "" else stringResource(R.string.minutes_left, minutesLeft),
                color = gruvboxGray,
                fontSize = 12.sp
            )
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
            description = stringResource(R.string.prev_section),
            enabled = active,
            onClick = onPrevious
        )
        TtsButton(
            icon = if (isSpeaking) Icons.Default.Pause else Icons.Default.PlayArrow,
            description = when {
                isSpeaking -> stringResource(R.string.pause)
                isPaused -> stringResource(R.string.resume)
                else -> stringResource(R.string.read_aloud)
            },
            tint = if (active) gruvboxOrange else gruvboxText,
            onClick = onPlayPause
        )
        TtsButton(
            icon = Icons.Default.SkipNext,
            description = stringResource(R.string.next_section),
            enabled = active,
            onClick = onNext
        )
        TtsButton(
            icon = Icons.Default.Stop,
            description = stringResource(R.string.stop_reading),
            enabled = active,
            tint = if (active) gruvboxRed else gruvboxGray,
            onClick = onStop
        )

        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = when {
                isSpeaking -> stringResource(R.string.reading)
                isPaused -> stringResource(R.string.paused)
                else -> stringResource(R.string.read_aloud)
            },
            color = if (active) gruvboxOrange else gruvboxGray,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )

        TtsButton(
            icon = Icons.Default.Tune,
            description = stringResource(R.string.choose_voice),
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
            .background(if (enabled) gruvboxButton else gruvboxButtonDisabled, RoundedCornerShape(8.dp))
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) tint else gruvboxGray
        )
    }
}

@Composable
private fun CodeBlock(
    source: String,
    isSelected: Boolean,
    fontSize: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val (lang, code) = remember(source) { parseCodeBlock(source) }
    var boxModifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 2.dp)
    if (isSelected) {
        boxModifier = boxModifier.border(1.5.dp, gruvboxBlue, RoundedCornerShape(8.dp))
    }

    Column(modifier = boxModifier) {
        if (lang.isNotBlank()) {
            Text(
                text = lang,
                color = gruvboxGray,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(gruvboxBg)
                    .padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                TextView(ctx).apply {
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(16, 12, 16, 12)
                    isClickable = true
                    isFocusable = false
                    isLongClickable = true
                }
            },
            update = { tv ->
                tv.textSize = fontSize - 2f
                tv.setTextColor(gruvboxText.toArgb())
                tv.setBackgroundColor(gruvboxBg.toArgb())
                tv.text = highlightCode(code)
                tv.setOnClickListener { onClick() }
                tv.setOnLongClickListener { onLongClick(); true }
            }
        )
    }
}

@Composable
private fun TaskListBlock(
    source: String,
    isSelected: Boolean,
    fontSize: Float,
    onToggle: (Int) -> Unit,
    onClick: () -> Unit
) {
    val lines = remember(source) { source.split("\n") }
    var columnModifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 2.dp)
        .background(gruvboxSurface, RoundedCornerShape(8.dp))
    if (isSelected) {
        columnModifier = columnModifier.border(1.5.dp, gruvboxBlue, RoundedCornerShape(8.dp))
    }

    Column(modifier = columnModifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        lines.forEachIndexed { i, line ->
            val task = taskLineRegex.matchEntire(line)
            if (task != null) {
                val checked = task.groupValues[3].lowercase() == "x"
                val label = task.groupValues[4]
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { onToggle(i) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = gruvboxOrange,
                            uncheckedColor = gruvboxGray,
                            checkmarkColor = gruvboxBg
                        )
                    )
                    Text(
                        text = label,
                        color = gruvboxText,
                        fontSize = fontSize.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onClick)
                    )
                }
            } else if (line.isNotBlank()) {
                Text(
                    text = line,
                    color = gruvboxText,
                    fontSize = fontSize.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onClick)
                        .padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun MermaidBlock(source: String, isSelected: Boolean, onClick: () -> Unit) {
    val code = remember(source) { parseCodeBlock(source).second }
    var mod = Modifier
        .fillMaxWidth()
        .padding(vertical = 2.dp)
        .height(320.dp)
        .clickable(onClick = onClick)
    if (isSelected) {
        mod = mod.border(1.5.dp, gruvboxBlue, RoundedCornerShape(8.dp))
    }
    AndroidView(
        modifier = mod,
        factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                settings.javaScriptEnabled = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { web ->
            web.loadDataWithBaseURL("https://cdn.jsdelivr.net", mermaidHtml(code), "text/html", "utf-8", null)
        }
    )
}

private fun mermaidHtml(code: String): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<script src="https://cdn.jsdelivr.net/npm/mermaid/dist/mermaid.min.js"></script>
</head>
<body style="margin:0;background:transparent;">
<pre class="mermaid">$code</pre>
<script>mermaid.initialize({ startOnLoad: true, theme: 'dark' });</script>
</body>
</html>
""".trimIndent()

@Composable
private fun RenderedBlock(
    markwon: Markwon,
    source: String,
    isReading: Boolean,
    isSelected: Boolean,
    highlight: WordHighlight?,
    fontSize: Float,
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
                setPadding(16, 8, 16, 8)
                isClickable = true
                isFocusable = false
                isLongClickable = true
            }
        },
        update = { tv ->
            tv.textSize = fontSize
            tv.setTextColor(gruvboxText.toArgb())
            tv.setBackgroundColor(gruvboxSurface.toArgb())
            markwon.setMarkdown(tv, removeComments(source))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BlockEditField(initial: String, fontSize: Float, onCommit: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focusRequester = remember { FocusRequester() }
    var hasFocused by remember { mutableStateOf(false) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    // Mantém o campo em edição visível quando o teclado abre/fecha (o inset muda).
    LaunchedEffect(hasFocused, imeBottom) {
        if (hasFocused) runCatching { bringIntoView.bringIntoView() }
    }

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
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp),
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bringIntoView)
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
                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.finish_edit), tint = gruvboxLilac)
            }
        }
    }
}

/**
 * Tabela editável no modo de visualização (WYSIWYG): grade de células editáveis com
 * botões para adicionar/remover linhas e colunas. Cada mudança reescreve o Markdown.
 */
@Composable
private fun TableBlock(source: String, fontSize: Float, onChange: (String) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    // Estado imutável da tabela; cada edição cria uma cópia nova (recompoe e reescreve o md).
    var table by remember { mutableStateOf(parseTable(source)) }
    fun apply(newTable: MarkdownTable) {
        table = newTable
        onChange(buildTable(newTable))
    }

    val cellColors = TextFieldDefaults.colors(
        focusedContainerColor = gruvboxBg, unfocusedContainerColor = gruvboxBg,
        focusedTextColor = gruvboxText, unfocusedTextColor = gruvboxText,
        cursorColor = gruvboxOrange,
        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
    )
    val cellWidth = 130.dp

    fun alignOf(c: Int) = table.aligns.getOrElse(c) { ColAlign.NONE }
    fun textAlignFor(a: ColAlign) = when (a) {
        ColAlign.CENTER -> TextAlign.Center
        ColAlign.RIGHT -> TextAlign.End
        else -> TextAlign.Start
    }
    fun iconFor(a: ColAlign) = when (a) {
        ColAlign.CENTER -> Icons.Default.FormatAlignCenter
        ColAlign.RIGHT -> Icons.Default.FormatAlignRight
        else -> Icons.Default.FormatAlignLeft
    }
    fun cycleAlign(a: ColAlign) = when (a) {
        ColAlign.NONE, ColAlign.LEFT -> ColAlign.CENTER
        ColAlign.CENTER -> ColAlign.RIGHT
        ColAlign.RIGHT -> ColAlign.NONE
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.5.dp, gruvboxLilac, RoundedCornerShape(8.dp))
            .background(gruvboxSurface, RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            // Cabeçalho (negrito) + botão de remover a última coluna.
            Row(verticalAlignment = Alignment.CenterVertically) {
                table.headers.forEachIndexed { c, h ->
                    TextField(
                        value = h,
                        onValueChange = { nv ->
                            apply(table.copy(headers = table.headers.toMutableList().also { it[c] = nv }))
                        },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = fontSize.sp, fontWeight = FontWeight.Bold, textAlign = textAlignFor(alignOf(c))),
                        colors = cellColors,
                        modifier = Modifier.width(cellWidth).padding(2.dp)
                    )
                }
                IconButton(
                    onClick = {
                        if (table.headers.size > 1) {
                            val last = table.headers.size - 1
                            apply(
                                table.copy(
                                    headers = table.headers.filterIndexed { i, _ -> i != last },
                                    rows = table.rows.map { row -> row.filterIndexed { i, _ -> i != last } }
                                )
                            )
                        }
                    },
                    enabled = table.headers.size > 1,
                    modifier = Modifier.size(36.dp)
                ) { Icon(Icons.Default.Close, stringResource(R.string.remove), tint = if (table.headers.size > 1) gruvboxGray else gruvboxSurface) }
            }
            // Alinhamento por coluna (toca para alternar esquerda/centro/direita).
            Row(verticalAlignment = Alignment.CenterVertically) {
                table.headers.indices.forEach { c ->
                    val a = alignOf(c)
                    Box(modifier = Modifier.width(cellWidth), contentAlignment = Alignment.Center) {
                        IconButton(
                            onClick = {
                                apply(table.copy(aligns = table.headers.indices.map { i -> if (i == c) cycleAlign(alignOf(i)) else alignOf(i) }))
                            },
                            modifier = Modifier.size(32.dp)
                        ) { Icon(iconFor(a), stringResource(R.string.table_align), tint = if (a == ColAlign.NONE) gruvboxGray else gruvboxOrange) }
                    }
                }
            }
            // Linhas de dados + botão de remover a linha.
            table.rows.forEachIndexed { r, row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    table.headers.indices.forEach { c ->
                        TextField(
                            value = row.getOrElse(c) { "" },
                            onValueChange = { nv ->
                                apply(table.copy(rows = table.rows.mapIndexed { ri, rr ->
                                    if (ri == r) {
                                        val cells = rr.toMutableList()
                                        while (cells.size < table.headers.size) cells.add("")
                                        cells[c] = nv
                                        cells
                                    } else rr
                                }))
                            },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = fontSize.sp, textAlign = textAlignFor(alignOf(c))),
                            colors = cellColors,
                            modifier = Modifier.width(cellWidth).padding(2.dp)
                        )
                    }
                    IconButton(
                        onClick = { apply(table.copy(rows = table.rows.filterIndexed { i, _ -> i != r })) },
                        modifier = Modifier.size(36.dp)
                    ) { Icon(Icons.Default.Close, stringResource(R.string.remove), tint = gruvboxGray) }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                apply(
                    table.copy(
                        headers = table.headers + context.getString(R.string.table_col_name, table.headers.size + 1),
                        rows = table.rows.map { it + "" }
                    )
                )
            }) {
                Icon(Icons.Default.Add, null, tint = gruvboxOrange, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.table_columns), color = gruvboxOrange, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = {
                apply(table.copy(rows = table.rows + listOf(List(table.headers.size) { "" })))
            }) {
                Icon(Icons.Default.Add, null, tint = gruvboxOrange, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.table_rows), color = gruvboxOrange, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onDone, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.finish_edit), tint = gruvboxLilac)
            }
        }
    }
}
