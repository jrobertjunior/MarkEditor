package com.creepybubble.markeditor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.creepybubble.markeditor.ui.theme.MarkEditorTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.InputStreamReader
import java.io.OutputStreamWriter

fun getFileName(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) result = it.getString(index)
            }
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) result = result?.substring(cut + 1)
    }
    return result ?: "Desconhecido.md"
}

/** Lista os arquivos markdown de uma pasta escolhida via árvore SAF. */
fun listMarkdownInTree(context: Context, treeUri: Uri): List<Pair<Uri, String>> {
    val result = mutableListOf<Pair<Uri, String>>()
    try {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0)
                val name = c.getString(1) ?: continue
                if (name.endsWith(".md", true) || name.endsWith(".markdown", true)) {
                    result.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId) to name)
                }
            }
        }
    } catch (e: Exception) {
        // Pasta inacessível ou vazia; devolve o que tiver.
    }
    return result.sortedBy { it.second.lowercase() }
}

class MainActivity : ComponentActivity() {
    private val pendingOpenUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Só na primeira criação (evita reabrir o arquivo ao girar a tela).
        if (savedInstanceState == null) pendingOpenUri.value = extractUri(intent)
        setContent {
            MarkEditorTheme {
                MarkEditorApp(
                    pendingOpenUri = pendingOpenUri.value,
                    onPendingOpenConsumed = { pendingOpenUri.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractUri(intent)?.let { pendingOpenUri.value = it }
    }

    /** Extrai o Uri de um intent de VIEW/EDIT/SEND, se houver. */
    private fun extractUri(intent: Intent?): Uri? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkEditorApp(
    pendingOpenUri: Uri? = null,
    onPendingOpenConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // Restaura a última sessão (abas, textos, aba ativa e posição do cursor).
    val restored = remember { SessionStore.load(context) }
    val documents = remember {
        mutableStateListOf<Document>().apply {
            val docs = restored?.docs
            if (!docs.isNullOrEmpty()) {
                docs.forEach { d ->
                    val len = d.text.length
                    add(
                        Document(
                            name = d.name,
                            uri = d.uri?.let { Uri.parse(it) },
                            initialText = d.text,
                            initialSelection = TextRange(
                                d.selStart.coerceIn(0, len),
                                d.selEnd.coerceIn(0, len)
                            ),
                            initialScrollIndex = d.scrollIndex,
                            initialScrollOffset = d.scrollOffset
                        )
                    )
                }
            } else {
                add(Document())
            }
        }
    }
    var selectedIndex by remember {
        mutableIntStateOf((restored?.selectedIndex ?: 0).coerceIn(0, maxOf(0, documents.size - 1)))
    }

    val safeIndex = selectedIndex.coerceIn(0, maxOf(0, documents.size - 1))
    val currentDoc = documents[safeIndex]

    var isPreviewMode by remember { mutableStateOf(restored?.previewMode ?: false) }
    // Muda só quando o bloco visível no preview muda, para disparar o salvamento sem exagero.
    var scrollTick by remember { mutableIntStateOf(0) }

    // Salva a sessão automaticamente (com um pequeno atraso) sempre que algo muda.
    LaunchedEffect(documents.size, selectedIndex, isPreviewMode, currentDoc.textState, currentDoc.name, scrollTick) {
        delay(400)
        SessionStore.save(
            context,
            SessionSnapshot(
                selectedIndex = safeIndex,
                previewMode = isPreviewMode,
                docs = documents.map { d ->
                    DocSnapshot(
                        name = d.name,
                        uri = d.uri?.toString(),
                        text = d.textState.text,
                        selStart = d.textState.selection.start,
                        selEnd = d.textState.selection.end,
                        scrollIndex = d.previewScrollIndex,
                        scrollOffset = d.previewScrollOffset
                    )
                }
            )
        )
    }
    var jumpToIndex by remember { mutableStateOf<Int?>(null) }
    val focusRequester = remember { FocusRequester() }

    var showRenameDialog by remember { mutableStateOf<Document?>(null) }
    var newFileName by remember { mutableStateOf("") }

    var showLinkDialog by remember { mutableStateOf(false) }
    var linkText by remember { mutableStateOf("") }
    var linkUrl by remember { mutableStateOf("") }

    // Offset do cursor a partir do qual iniciar a leitura ao entrar no preview.
    var readFromOffset by remember { mutableStateOf<Int?>(null) }

    val appPrefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    var autoSaveEnabled by remember { mutableStateOf(appPrefs.getBoolean("autosave", true)) }
    var showExportMenu by remember { mutableStateOf(false) }

    // Aplica o tema salvo uma única vez.
    remember {
        appPrefs.getString("palette", null)?.let { saved ->
            appPalettes.firstOrNull { it.name == saved }?.let { applyPalette(it) }
        }
        true
    }
    var fontSize by remember { mutableStateOf(appPrefs.getFloat("fontsize", 16f)) }
    var recents by remember { mutableStateOf(RecentFiles.load(appPrefs)) }

    var showFolderDialog by remember { mutableStateOf(false) }
    var folderFiles by remember { mutableStateOf<List<Pair<Uri, String>>>(emptyList()) }

    // Permissão de notificação (Android 13+) para a notificação de mídia da leitura.
    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val tocItems = remember(currentDoc.textState.text) { extractToc(currentDoc.textState.text) }

    val updateTextState = { newState: TextFieldValue ->
        currentDoc.textState = newState
        currentDoc.undoRedoManager.record(newState)
    }

    val applyInlineTag = { tagOpen: String, tagClose: String ->
        val text = currentDoc.textState.text
        val selection = currentDoc.textState.selection
        val selectedText = text.substring(selection.start, selection.end)
        val newText = text.replaceRange(selection.start, selection.end, "$tagOpen$selectedText$tagClose")
        val newCursorPos = selection.start + tagOpen.length + selectedText.length
        updateTextState(TextFieldValue(text = newText, selection = TextRange(newCursorPos)))
    }

    val applyBlockTag = { tagPrefix: String ->
        val text = currentDoc.textState.text
        val cursor = currentDoc.textState.selection.start
        val lineStart = text.lastIndexOf('\n', cursor - 1).let { if (it == -1) 0 else it + 1 }
        val newText = text.substring(0, lineStart) + tagPrefix + text.substring(lineStart)
        val newCursorPos = cursor + tagPrefix.length
        updateTextState(TextFieldValue(text = newText, selection = TextRange(newCursorPos)))
    }

    val insertAtCursor = { snippet: String ->
        val text = currentDoc.textState.text
        val cursor = currentDoc.textState.selection.start
        val newText = text.substring(0, cursor) + snippet + text.substring(cursor)
        updateTextState(TextFieldValue(text = newText, selection = TextRange(cursor + snippet.length)))
    }

    val readFile = { uri: Uri -> context.contentResolver.openInputStream(uri)?.use { InputStreamReader(it).readText() } ?: "" }
    val saveFile = { uri: Uri, content: String ->
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { OutputStreamWriter(it).use { writer -> writer.write(content) } }
            true
        } catch (e: Exception) {
            false
        }
    }

    // Mantém o acesso ao arquivo entre reinícios do app (para conseguir salvar de volta).
    val persistUriPermission = { uri: Uri ->
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // Alguns provedores não concedem gravação persistente; tudo bem.
        }
    }

    val openDocumentUri = { uri: Uri ->
        persistUriPermission(uri)
        val fileName = getFileName(context, uri)
        val content = readFile(uri)
        documents.add(Document(name = fileName, uri = uri, initialText = content))
        selectedIndex = documents.size - 1
        RecentFiles.add(appPrefs, uri.toString(), fileName)
        recents = RecentFiles.load(appPrefs)
    }

    val openFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { openDocumentUri(it) }
    }

    // Abre um arquivo recebido do sistema (Abrir com / compartilhar .md).
    LaunchedEffect(pendingOpenUri) {
        val uri = pendingOpenUri ?: return@LaunchedEffect
        openDocumentUri(uri)
        onPendingOpenConsumed()
    }

    val openTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        treeUri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { /* segue mesmo sem permissão persistente */ }
            folderFiles = listMarkdownInTree(context, it)
            showFolderDialog = true
        }
    }

    val saveAsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri?.let {
            persistUriPermission(it)
            if (saveFile(it, currentDoc.textState.text)) currentDoc.savedText = currentDoc.textState.text
            currentDoc.uri = it
            currentDoc.name = getFileName(context, it)
            RecentFiles.add(appPrefs, it.toString(), currentDoc.name)
            recents = RecentFiles.load(appPrefs)
        }
    }

    val exportHtmlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it)?.use { os ->
                    os.write(renderFullHtml(currentDoc.name, currentDoc.textState.text).toByteArray())
                }
            } catch (e: Exception) { /* ignora falha de exportação */ }
        }
    }

    val exportPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it)?.use { os ->
                    writePdf(context, currentDoc.textState.text, os)
                }
            } catch (e: Exception) { /* ignora falha de exportação */ }
        }
    }

    // Auto-save: grava no arquivo (se houver) pouco depois de parar de digitar.
    LaunchedEffect(currentDoc.textState.text, currentDoc.uri, autoSaveEnabled) {
        if (!autoSaveEnabled) return@LaunchedEffect
        val uri = currentDoc.uri ?: return@LaunchedEffect
        if (currentDoc.textState.text == currentDoc.savedText) return@LaunchedEffect
        delay(2000)
        if (autoSaveEnabled && currentDoc.uri == uri && currentDoc.textState.text != currentDoc.savedText) {
            if (saveFile(uri, currentDoc.textState.text)) currentDoc.savedText = currentDoc.textState.text
        }
    }

    showRenameDialog?.let { docToRename ->
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Renomear Arquivo", color = gruvboxText) },
            containerColor = gruvboxSurface,
            textContentColor = gruvboxText,
            titleContentColor = gruvboxOrange,
            text = {
                TextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = gruvboxBg, unfocusedContainerColor = gruvboxBg,
                        focusedTextColor = gruvboxText, unfocusedTextColor = gruvboxText,
                        cursorColor = gruvboxOrange
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    docToRename.name = if (newFileName.endsWith(".md")) newFileName else "$newFileName.md"
                    showRenameDialog = null
                }) { Text("Salvar", color = gruvboxOrange) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) { Text("Cancelar", color = gruvboxGray) }
            }
        )
    }

    if (showLinkDialog) {
        val linkFieldColors = TextFieldDefaults.colors(
            focusedContainerColor = gruvboxBg, unfocusedContainerColor = gruvboxBg,
            focusedTextColor = gruvboxText, unfocusedTextColor = gruvboxText,
            cursorColor = gruvboxOrange
        )
        AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            title = { Text("Inserir link", color = gruvboxOrange) },
            containerColor = gruvboxSurface,
            text = {
                Column {
                    TextField(
                        value = linkText, onValueChange = { linkText = it }, singleLine = true,
                        label = { Text("Texto", color = gruvboxGray) }, colors = linkFieldColors
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = linkUrl, onValueChange = { linkUrl = it }, singleLine = true,
                        label = { Text("URL", color = gruvboxGray) }, colors = linkFieldColors
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val sel = currentDoc.textState.selection
                    val text = currentDoc.textState.text
                    val label = linkText.ifBlank { "link" }
                    val insert = "[$label]($linkUrl)"
                    val newText = text.replaceRange(sel.start, sel.end, insert)
                    updateTextState(TextFieldValue(newText, TextRange(sel.start + insert.length)))
                    showLinkDialog = false
                }) { Text("Inserir", color = gruvboxOrange) }
            },
            dismissButton = {
                TextButton(onClick = { showLinkDialog = false }) { Text("Cancelar", color = gruvboxGray) }
            }
        )
    }

    if (showFolderDialog) {
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            title = { Text("Arquivos da pasta", color = gruvboxOrange) },
            containerColor = gruvboxSurface,
            text = {
                if (folderFiles.isEmpty()) {
                    Text("Nenhum arquivo .md encontrado nesta pasta.", color = gruvboxGray)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(folderFiles) { (fileUri, name) ->
                            Box(modifier = Modifier.fillMaxWidth().clickable {
                                openDocumentUri(fileUri)
                                showFolderDialog = false
                            }.padding(vertical = 12.dp, horizontal = 8.dp)) {
                                Text(name, color = gruvboxText, maxLines = 1)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFolderDialog = false }) { Text("Fechar", color = gruvboxOrange) }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = gruvboxBg, drawerContentColor = gruvboxText, modifier = Modifier.width(300.dp)) {
                Text("Índice", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall, color = gruvboxOrange, fontWeight = FontWeight.Bold)
                HorizontalDivider(color = gruvboxSurface)
                if (tocItems.isEmpty()) {
                    Text("Nenhum capítulo encontrado.", modifier = Modifier.padding(16.dp), color = gruvboxGray)
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(tocItems) { item ->
                            Box(modifier = Modifier.fillMaxWidth().clickable {
                                updateTextState(currentDoc.textState.copy(selection = TextRange(item.index)))
                                jumpToIndex = item.index
                                coroutineScope.launch { drawerState.close(); if (!isPreviewMode) focusRequester.requestFocus() }
                            }.padding(vertical = 12.dp, horizontal = 16.dp).padding(start = ((item.level - 1) * 16).dp)) {
                                Text(item.label, color = gruvboxText, style = if (item.level == 1) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                if (recents.isNotEmpty()) {
                    HorizontalDivider(color = gruvboxSurface)
                    Text("Recentes", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium, color = gruvboxOrange, fontWeight = FontWeight.Bold)
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(recents) { rf ->
                            Box(modifier = Modifier.fillMaxWidth().clickable {
                                openDocumentUri(Uri.parse(rf.uri))
                                coroutineScope.launch { drawerState.close() }
                            }.padding(vertical = 12.dp, horizontal = 16.dp)) {
                                Text(rf.name, color = gruvboxText, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { }, // Título removido para a barra respirar e não esmagar os ícones!
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = gruvboxBg),
                    navigationIcon = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, "Índice", tint = gruvboxOrange) }
                    },
                    actions = {
                        IconButton(onClick = {
                            documents.add(Document())
                            selectedIndex = documents.size - 1
                        }) { Icon(Icons.Default.Add, "Novo Arquivo", tint = gruvboxText) }

                        IconButton(onClick = { openFileLauncher.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FolderOpen, "Abrir", tint = gruvboxText) }

                        IconButton(onClick = {
                            val uri = currentDoc.uri
                            if (uri != null && saveFile(uri, currentDoc.textState.text)) {
                                currentDoc.savedText = currentDoc.textState.text
                            } else {
                                saveAsLauncher.launch(currentDoc.name)
                            }
                        }) {
                            Icon(Icons.Default.Save, "Salvar", tint = gruvboxText)
                        }

                        IconButton(onClick = { isPreviewMode = !isPreviewMode }) { Icon(if (isPreviewMode) Icons.Default.Edit else Icons.Default.Visibility, "Alternar", tint = gruvboxText) }

                        Box {
                            IconButton(onClick = { showExportMenu = true }) {
                                Icon(Icons.Default.MoreVert, "Mais opções", tint = gruvboxText)
                            }
                            DropdownMenu(
                                expanded = showExportMenu,
                                onDismissRequest = { showExportMenu = false },
                                modifier = Modifier.background(gruvboxSurface)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Abrir pasta", color = gruvboxText) },
                                    onClick = {
                                        showExportMenu = false
                                        openTreeLauncher.launch(null)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Salvar como…", color = gruvboxText) },
                                    onClick = {
                                        showExportMenu = false
                                        saveAsLauncher.launch(currentDoc.name)
                                    }
                                )
                                HorizontalDivider(color = gruvboxBg)
                                DropdownMenuItem(
                                    text = { Text("Exportar HTML", color = gruvboxText) },
                                    onClick = {
                                        showExportMenu = false
                                        exportHtmlLauncher.launch(currentDoc.name.removeSuffix(".md") + ".html")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Exportar PDF", color = gruvboxText) },
                                    onClick = {
                                        showExportMenu = false
                                        exportPdfLauncher.launch(currentDoc.name.removeSuffix(".md") + ".pdf")
                                    }
                                )
                                HorizontalDivider(color = gruvboxBg)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (autoSaveEnabled) "Auto-salvar: ligado" else "Auto-salvar: desligado",
                                            color = if (autoSaveEnabled) gruvboxOrange else gruvboxGray
                                        )
                                    },
                                    onClick = {
                                        autoSaveEnabled = !autoSaveEnabled
                                        appPrefs.edit().putBoolean("autosave", autoSaveEnabled).apply()
                                        showExportMenu = false
                                    }
                                )

                                HorizontalDivider(color = gruvboxBg)
                                DropdownMenuItem(
                                    text = { Text("Fonte: ${fontSize.toInt()}", color = gruvboxText) },
                                    trailingIcon = {
                                        Row {
                                            IconButton(onClick = {
                                                fontSize = (fontSize - 1f).coerceAtLeast(12f)
                                                appPrefs.edit().putFloat("fontsize", fontSize).apply()
                                            }) { Icon(Icons.Default.Remove, "Diminuir", tint = gruvboxOrange) }
                                            IconButton(onClick = {
                                                fontSize = (fontSize + 1f).coerceAtMost(30f)
                                                appPrefs.edit().putFloat("fontsize", fontSize).apply()
                                            }) { Icon(Icons.Default.Add, "Aumentar", tint = gruvboxOrange) }
                                        }
                                    },
                                    onClick = { }
                                )

                                HorizontalDivider(color = gruvboxBg)
                                appPalettes.forEach { palette ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "Tema: ${palette.name}",
                                                color = if (palette.name == currentPaletteName) gruvboxOrange else gruvboxText
                                            )
                                        },
                                        onClick = {
                                            applyPalette(palette)
                                            appPrefs.edit().putString("palette", palette.name).apply()
                                            showExportMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
            },
            bottomBar = {
                ScrollableTabRow(
                    selectedTabIndex = safeIndex,
                    containerColor = gruvboxSurface,
                    contentColor = gruvboxOrange,
                    edgePadding = 8.dp,
                    indicator = { tabPositions ->
                        val indicatorIndex = minOf(safeIndex, tabPositions.lastIndex)
                        if (indicatorIndex >= 0) {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[indicatorIndex]),
                                color = gruvboxOrange
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding() // Proteção contra a barra da Samsung
                ) {
                    documents.forEachIndexed { index, doc ->
                        Tab(
                            selected = safeIndex == index,
                            onClick = { selectedIndex = index },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (doc.isDirty) {
                                        Box(modifier = Modifier.size(7.dp).background(gruvboxYellow, CircleShape))
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text(
                                        text = doc.name,
                                        color = if (safeIndex == index) gruvboxOrange else gruvboxText,
                                        fontWeight = if (safeIndex == index) FontWeight.Bold else FontWeight.Normal
                                    )

                                    if (safeIndex == index) {
                                        IconButton(
                                            onClick = {
                                                newFileName = doc.name.removeSuffix(".md")
                                                showRenameDialog = doc
                                            },
                                            modifier = Modifier.size(24.dp).padding(start = 4.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, "Renomear", tint = gruvboxOrange, modifier = Modifier.size(14.dp))
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    IconButton(
                                        onClick = {
                                            documents.removeAt(index)
                                            if (documents.isEmpty()) {
                                                documents.add(Document())
                                            }
                                            selectedIndex = selectedIndex.coerceIn(0, documents.size - 1)
                                        },
                                        modifier = Modifier.size(16.dp)
                                    ) {
                                        Icon(Icons.Default.Close, "Fechar", tint = if (safeIndex == index) gruvboxOrange else gruvboxGray)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding().background(gruvboxBg)) {
                val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

                val previewPane: @Composable () -> Unit = {
                    Surface(modifier = Modifier.fillMaxSize().padding(16.dp), shape = RoundedCornerShape(12.dp), color = gruvboxSurface) {
                        // key() garante estado de scroll/seleção/leitura próprio para cada aba.
                        key(currentDoc.id) {
                            MarkdownPreview(
                                text = currentDoc.textState.text,
                                onTextChange = { newText ->
                                    updateTextState(TextFieldValue(text = newText, selection = TextRange(newText.length)))
                                },
                                jumpToIndex = jumpToIndex,
                                onJumpConsumed = { jumpToIndex = null },
                                initialScrollIndex = currentDoc.previewScrollIndex,
                                initialScrollOffset = currentDoc.previewScrollOffset,
                                onScrollChanged = { i, o ->
                                    if (currentDoc.previewScrollIndex != i) scrollTick++
                                    currentDoc.previewScrollIndex = i
                                    currentDoc.previewScrollOffset = o
                                },
                                startReadingFromOffset = readFromOffset,
                                onStartReadingConsumed = { readFromOffset = null },
                                fontSize = fontSize,
                                documentTitle = currentDoc.name,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                val editorPane: @Composable () -> Unit = {
                    Column(modifier = Modifier.fillMaxSize()) {
                    // Nova estrutura: Row fixa contendo a LazyRow rolável
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(gruvboxSurface)
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Botões FIXOS (Undo / Redo)
                        ToolBarIconButton(Icons.Default.Undo, onClick = { currentDoc.undoRedoManager.undo { currentDoc.textState = it } }, enabled = currentDoc.undoRedoManager.canUndo, tint = if (currentDoc.undoRedoManager.canUndo) gruvboxText else gruvboxGray, label = "Desfazer")
                        Spacer(modifier = Modifier.width(4.dp))
                        ToolBarIconButton(Icons.Default.Redo, onClick = { currentDoc.undoRedoManager.redo { currentDoc.textState = it } }, enabled = currentDoc.undoRedoManager.canRedo, tint = if (currentDoc.undoRedoManager.canRedo) gruvboxText else gruvboxGray, label = "Refazer")

                        Spacer(modifier = Modifier.width(8.dp))

                        // 2. Divisor visual elegante
                        Box(modifier = Modifier.width(2.dp).height(24.dp).background(gruvboxBg))

                        Spacer(modifier = Modifier.width(8.dp))

                        // 3. Botões ROLÁVEIS ocupando o resto do espaço
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f) // A mágica que faz rolar apenas aqui dentro
                        ) {
                            item { ToolBarButton("H1", onClick = { applyBlockTag("# ") }, label = "Título 1") }
                            item { ToolBarButton("H2", onClick = { applyBlockTag("## ") }, label = "Título 2") }
                            item { ToolBarButton("H3", onClick = { applyBlockTag("### ") }, label = "Título 3") }
                            item { ToolBarIconButton(Icons.Default.FormatBold, onClick = { applyInlineTag("**", "**") }, label = "Negrito") }
                            item { ToolBarIconButton(Icons.Default.FormatItalic, onClick = { applyInlineTag("*", "*") }, label = "Itálico") }
                            item { ToolBarIconButton(Icons.Default.FormatStrikethrough, onClick = { applyInlineTag("~~", "~~") }, label = "Tachado") }
                            item { ToolBarIconButton(Icons.Default.FormatQuote, onClick = { applyBlockTag("> ") }, label = "Citação") }
                            item { ToolBarIconButton(Icons.Default.Code, onClick = { applyInlineTag("`", "`") }, label = "Código") }
                            item { ToolBarIconButton(Icons.Default.FormatListBulleted, onClick = { applyBlockTag("- ") }, label = "Lista") }
                            item { ToolBarIconButton(Icons.Default.FormatListNumbered, onClick = { applyBlockTag("1. ") }, label = "Numerada") }
                            item { ToolBarIconButton(Icons.Default.CheckBox, onClick = { applyBlockTag("- [ ] ") }, label = "Tarefa") }
                            item {
                                ToolBarIconButton(Icons.Default.Link, onClick = {
                                    val sel = currentDoc.textState.selection
                                    linkText = currentDoc.textState.text.substring(sel.start, sel.end)
                                    linkUrl = ""
                                    showLinkDialog = true
                                }, label = "Link")
                            }
                            item { ToolBarIconButton(Icons.Default.HorizontalRule, onClick = { insertAtCursor("\n---\n") }, label = "Linha") }
                            item { ToolBarIconButton(Icons.Default.Comment, onClick = { insertAtCursor("<!-- comentário -->") }, label = "Comentário") }
                            item { ToolBarButton("Mermaid", onClick = { insertAtCursor("\n```mermaid\ngraph TD\n    A[Início] --> B[Fim]\n```\n") }, label = "Diagrama") }
                            item { ToolBarButton("TeX", onClick = { insertAtCursor("\n${'$'}${'$'}\nE = mc^2\n${'$'}${'$'}\n") }, label = "Fórmula") }
                            item {
                                ToolBarIconButton(Icons.Default.VolumeUp, onClick = {
                                    readFromOffset = currentDoc.textState.selection.start
                                    isPreviewMode = true
                                }, label = "Ler daqui")
                            }
                        }
                    }

                    TextField(
                        value = currentDoc.textState,
                        onValueChange = { updateTextState(continueListOnNewline(currentDoc.textState, it)) },
                        visualTransformation = MarkdownGruvboxTransformation(),
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp).clip(RoundedCornerShape(8.dp)).focusRequester(focusRequester),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = gruvboxBg, unfocusedContainerColor = gruvboxBg,
                            focusedTextColor = gruvboxText, unfocusedTextColor = gruvboxText,
                            cursorColor = gruvboxText, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                        )
                    )

                    // Barra de status: contagem de palavras/caracteres e tempo de leitura.
                    val wordCount = countWords(currentDoc.textState.text)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(gruvboxSurface)
                            .padding(horizontal = 12.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = buildString {
                                append("$wordCount palavras · ${currentDoc.textState.text.length} caract.")
                                if (wordCount > 0) append(" · ~${readingMinutes(wordCount)} min")
                            },
                            color = gruvboxGray,
                            fontSize = 12.sp
                        )
                    }
                    }
                }

                if (landscape) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) { editorPane() }
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) { previewPane() }
                    }
                } else {
                    if (isPreviewMode) previewPane() else editorPane()
                }
            }
        }
    }
}

// Botões com uma legenda pequena embaixo, para deixar claro o que cada um faz.
@Composable
fun ToolBarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = gruvboxText,
    label: String = ""
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.background(if (enabled) Color(0xFF504945) else Color(0xFF3C3836), RoundedCornerShape(8.dp))) {
            Icon(icon, contentDescription = label.ifEmpty { null }, tint = tint)
        }
        if (label.isNotEmpty()) {
            Text(label, fontSize = 9.sp, color = gruvboxGray, maxLines = 1)
        }
    }
}

@Composable
fun ToolBarButton(text: String, onClick: () -> Unit, label: String = "") {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = onClick, modifier = Modifier.background(Color(0xFF504945), RoundedCornerShape(8.dp)), contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(text, fontWeight = FontWeight.Bold, color = gruvboxText)
        }
        if (label.isNotEmpty()) {
            Text(label, fontSize = 9.sp, color = gruvboxGray, maxLines = 1)
        }
    }
}