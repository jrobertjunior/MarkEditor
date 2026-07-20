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
import androidx.compose.foundation.lazy.itemsIndexed
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
    val tabs = remember {
        // Reconstrói um Document a partir do snapshot persistido.
        fun docOf(d: DocSnapshot): Document {
            val len = d.text.length
            return Document(
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
        }
        mutableStateListOf<Tab>().apply {
            val savedTabs = restored?.tabs
            if (!savedTabs.isNullOrEmpty()) {
                savedTabs.forEach { t ->
                    if (t.type == "project" && t.parts.isNotEmpty()) {
                        val pid = if (t.id.isNotBlank()) {
                            try { java.util.UUID.fromString(t.id) } catch (e: Exception) { java.util.UUID.randomUUID() }
                        } else java.util.UUID.randomUUID()
                        val p = Project(id = pid, name = t.name, parts = t.parts.map { docOf(it) })
                        p.activeIndex = t.activeIndex.coerceIn(0, p.parts.size - 1)
                        add(p)
                    } else {
                        add(SingleTab(docOf(t.parts.firstOrNull() ?: DocSnapshot(t.name, null, "", 0, 0))))
                    }
                }
            } else {
                add(SingleTab(Document()))
            }
        }
    }
    var selectedIndex by remember {
        mutableIntStateOf((restored?.selectedIndex ?: 0).coerceIn(0, maxOf(0, tabs.size - 1)))
    }

    val safeIndex = selectedIndex.coerceIn(0, maxOf(0, tabs.size - 1))
    val currentTab = tabs[safeIndex]
    val currentProject = currentTab as? Project
    val currentDoc = currentTab.editable

    var isPreviewMode by remember { mutableStateOf(restored?.previewMode ?: false) }
    // Muda só quando o bloco visível no preview muda, para disparar o salvamento sem exagero.
    var scrollTick by remember { mutableIntStateOf(0) }

    // Salva a sessão automaticamente (com um pequeno atraso) sempre que algo muda.
    LaunchedEffect(tabs.size, selectedIndex, isPreviewMode, currentDoc.textState, currentTab.title, scrollTick) {
        delay(400)
        fun snap(d: Document) = DocSnapshot(
            name = d.name,
            uri = d.uri?.toString(),
            text = d.textState.text,
            selStart = d.textState.selection.start,
            selEnd = d.textState.selection.end,
            scrollIndex = d.previewScrollIndex,
            scrollOffset = d.previewScrollOffset
        )
        SessionStore.save(
            context,
            SessionSnapshot(
                selectedIndex = safeIndex,
                previewMode = isPreviewMode,
                tabs = tabs.map { t ->
                    when (t) {
                        is Project -> TabSnapshot("project", t.name, t.activeIndex, t.parts.map { snap(it) }, t.id.toString())
                        is SingleTab -> TabSnapshot("single", t.doc.name, 0, listOf(snap(t.doc)))
                    }
                }
            )
        )
    }
    var jumpToIndex by remember { mutableStateOf<Int?>(null) }
    val focusRequester = remember { FocusRequester() }

    var showRenameDialog by remember { mutableStateOf<Tab?>(null) }
    var newFileName by remember { mutableStateOf("") }

    var showLinkDialog by remember { mutableStateOf(false) }
    var linkText by remember { mutableStateOf("") }
    var linkUrl by remember { mutableStateOf("") }

    // Offset do cursor a partir do qual iniciar a leitura ao entrar no preview.
    var readFromOffset by remember { mutableStateOf<Int?>(null) }

    val appPrefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    var autoSaveEnabled by remember { mutableStateOf(appPrefs.getBoolean("autosave", true)) }
    var showExportMenu by remember { mutableStateOf(false) }
    var menuPage by remember { mutableStateOf("main") } // "main" | "themes" | "recents"

    // Aplica o tema salvo uma única vez.
    remember {
        appPrefs.getString("palette", null)?.let { saved ->
            appPalettes.firstOrNull { it.name == saved }?.let { applyPalette(it) }
        }
        true
    }
    var fontSize by remember { mutableStateOf(appPrefs.getFloat("fontsize", 16f)) }
    var recents by remember { mutableStateOf(RecentFiles.load(appPrefs)) }

    // Rascunho do diálogo de projeto: arquivos (na ordem) + nome, antes de confirmar.
    var showProjectDialog by remember { mutableStateOf(false) }
    var projectDraft by remember { mutableStateOf<List<Pair<Uri, String>>>(emptyList()) }
    var projectName by remember { mutableStateOf("Projeto") }
    // Se != null, o diálogo está EDITANDO um projeto aberto; se null, está criando um novo.
    var editingProject by remember { mutableStateOf<Project?>(null) }

    // Diálogo "Abrir projeto" (lista de projetos salvos).
    var showOpenProjectDialog by remember { mutableStateOf(false) }
    var savedProjects by remember { mutableStateOf(ProjectStore.load(context)) }

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
        tabs.add(SingleTab(Document(name = fileName, uri = uri, initialText = content)))
        selectedIndex = tabs.size - 1
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

    // Seleção múltipla para CRIAR um projeto: abre o diálogo de ordenação.
    val openProjectFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) {
            projectDraft = uris.map { u ->
                persistUriPermission(u)
                u to getFileName(context, u)
            }
            projectName = "Projeto"
            editingProject = null
            showProjectDialog = true
        }
    }

    // Seleção múltipla para ADICIONAR arquivos ao rascunho atual (criar ou editar).
    val addFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) {
            val novos = uris.map { u ->
                persistUriPermission(u)
                u to getFileName(context, u)
            }
            // Evita duplicar arquivos já presentes no rascunho.
            val existentes = projectDraft.map { it.first.toString() }.toSet()
            projectDraft = projectDraft + novos.filterNot { it.first.toString() in existentes }
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

    // Salva a aba atual. Projeto: grava cada parte no seu próprio arquivo.
    // Arquivo único sem uri: abre "Salvar como…".
    val saveCurrentTab = {
        val proj = currentProject
        if (proj != null) {
            proj.parts.forEach { part ->
                val u = part.uri
                if (u != null && saveFile(u, part.textState.text)) part.savedText = part.textState.text
            }
        } else {
            val uri = currentDoc.uri
            if (uri != null && saveFile(uri, currentDoc.textState.text)) {
                currentDoc.savedText = currentDoc.textState.text
            } else {
                saveAsLauncher.launch(currentDoc.name)
            }
        }
    }

    // Converte um projeto aberto na sua forma serializável (estrutura: nome + arquivos).
    val savedOf = { project: Project ->
        SavedProject(
            id = project.id.toString(),
            name = project.name,
            files = project.parts.mapNotNull { p -> p.uri?.let { it.toString() to p.name } }
        )
    }

    // Grava a estrutura de um projeto no armazenamento persistente (registro interno).
    val persistProject = { project: Project ->
        ProjectStore.upsert(context, savedOf(project))
        savedProjects = ProjectStore.load(context)
    }

    // Qual projeto será gravado no arquivo .mdproj escolhido pelo usuário.
    var projectToSaveFile by remember { mutableStateOf<Project?>(null) }

    // Salvar projeto EM ARQUIVO (.mdproj) num local escolhido pelo usuário.
    val saveProjectFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val proj = projectToSaveFile
        if (uri != null && proj != null) {
            try {
                context.contentResolver.openOutputStream(uri, "wt")?.use { os ->
                    os.write(ProjectStore.serialize(savedOf(proj)).toByteArray())
                }
            } catch (e: Exception) { /* ignora falha de gravação */ }
        }
        projectToSaveFile = null
    }

    // Abrir projeto DE UM ARQUIVO (.mdproj).
    val openProjectFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val text = readFile(it)
            val sp = ProjectStore.parse(text)
            if (sp != null) {
                val jaAberto = tabs.indexOfFirst { t -> t is Project && t.id.toString() == sp.id }
                if (jaAberto >= 0) {
                    selectedIndex = jaAberto
                } else {
                    val parts = sp.files.mapNotNull { (uriStr, fname) ->
                        try {
                            val u = Uri.parse(uriStr)
                            Document(name = fname, uri = u, initialText = readFile(u))
                        } catch (e: Exception) { null }
                    }
                    if (parts.isNotEmpty()) {
                        val pid = try { java.util.UUID.fromString(sp.id) } catch (e: Exception) { java.util.UUID.randomUUID() }
                        val proj = Project(id = pid, name = sp.name, parts = parts)
                        tabs.add(proj)
                        selectedIndex = tabs.size - 1
                        isPreviewMode = false
                        ProjectStore.upsert(context, sp.copy(id = pid.toString()))
                        savedProjects = ProjectStore.load(context)
                    }
                }
            }
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

    showRenameDialog?.let { tabToRename ->
        val isProject = tabToRename is Project
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text(if (isProject) "Renomear Projeto" else "Renomear Arquivo", color = gruvboxText) },
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
                    when (tabToRename) {
                        is Project -> {
                            tabToRename.name = newFileName.ifBlank { "Projeto" }
                            persistProject(tabToRename)
                        }
                        is SingleTab -> tabToRename.doc.name = if (newFileName.endsWith(".md")) newFileName else "$newFileName.md"
                    }
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

    if (showProjectDialog) {
        val editing = editingProject
        AlertDialog(
            onDismissRequest = { showProjectDialog = false },
            title = { Text(if (editing != null) "Gerenciar projeto" else "Novo projeto", color = gruvboxOrange) },
            containerColor = gruvboxSurface,
            text = {
                Column {
                    TextField(
                        value = projectName,
                        onValueChange = { projectName = it },
                        singleLine = true,
                        label = { Text("Nome do projeto", color = gruvboxGray) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = gruvboxBg, unfocusedContainerColor = gruvboxBg,
                            focusedTextColor = gruvboxText, unfocusedTextColor = gruvboxText,
                            cursorColor = gruvboxOrange
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Ordem dos arquivos", color = gruvboxGray, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { addFilesLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.Add, null, tint = gruvboxOrange, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Adicionar", color = gruvboxOrange, fontSize = 13.sp)
                        }
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        itemsIndexed(projectDraft) { index, (_, name) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${index + 1}.", color = gruvboxOrange, modifier = Modifier.width(24.dp))
                                Text(name, color = gruvboxText, maxLines = 1, modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = {
                                        if (index > 0) projectDraft = projectDraft.toMutableList().also { it.add(index - 1, it.removeAt(index)) }
                                    },
                                    enabled = index > 0,
                                    modifier = Modifier.size(32.dp)
                                ) { Icon(Icons.Default.KeyboardArrowUp, "Subir", tint = if (index > 0) gruvboxText else gruvboxGray) }
                                IconButton(
                                    onClick = {
                                        if (index < projectDraft.size - 1) projectDraft = projectDraft.toMutableList().also { it.add(index + 1, it.removeAt(index)) }
                                    },
                                    enabled = index < projectDraft.size - 1,
                                    modifier = Modifier.size(32.dp)
                                ) { Icon(Icons.Default.KeyboardArrowDown, "Descer", tint = if (index < projectDraft.size - 1) gruvboxText else gruvboxGray) }
                                IconButton(
                                    onClick = { projectDraft = projectDraft.toMutableList().also { it.removeAt(index) } },
                                    modifier = Modifier.size(32.dp)
                                ) { Icon(Icons.Default.Close, "Remover", tint = gruvboxGray) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = projectDraft.isNotEmpty(),
                    onClick = {
                        val name = projectName.ifBlank { "Projeto" }
                        if (editing != null) {
                            // Reconcilia mantendo as partes existentes (com suas edições) e
                            // criando Document novo só para arquivos recém-adicionados.
                            val porUri = editing.parts.associateBy { it.uri?.toString() }
                            val novasPartes = projectDraft.map { (u, fname) ->
                                porUri[u.toString()] ?: Document(name = fname, uri = u, initialText = readFile(u))
                            }
                            editing.name = name
                            editing.parts.clear()
                            editing.parts.addAll(novasPartes)
                            editing.activeIndex = editing.activeIndex.coerceIn(0, editing.parts.size - 1)
                            persistProject(editing)
                        } else {
                            val parts = projectDraft.map { (u, fname) ->
                                Document(name = fname, uri = u, initialText = readFile(u))
                            }
                            val proj = Project(name = name, parts = parts)
                            tabs.add(proj)
                            selectedIndex = tabs.size - 1
                            isPreviewMode = false
                            persistProject(proj)
                        }
                        editingProject = null
                        showProjectDialog = false
                    }
                ) { Text(if (editing != null) "Salvar" else "Criar", color = if (projectDraft.isNotEmpty()) gruvboxOrange else gruvboxGray) }
            },
            dismissButton = {
                TextButton(onClick = { showProjectDialog = false; editingProject = null }) { Text("Cancelar", color = gruvboxGray) }
            }
        )
    }

    if (showOpenProjectDialog) {
        AlertDialog(
            onDismissRequest = { showOpenProjectDialog = false },
            title = { Text("Abrir projeto", color = gruvboxOrange) },
            containerColor = gruvboxSurface,
            text = {
                if (savedProjects.isEmpty()) {
                    Text("Nenhum projeto salvo ainda.", color = gruvboxGray)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(savedProjects, key = { it.id }) { sp ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(
                                    modifier = Modifier.weight(1f).clickable {
                                        // Se já estiver aberto, apenas foca a aba.
                                        val jaAberto = tabs.indexOfFirst { it is Project && it.id.toString() == sp.id }
                                        if (jaAberto >= 0) {
                                            selectedIndex = jaAberto
                                        } else {
                                            // Reabre o projeto lendo os arquivos atuais do disco.
                                            val parts = sp.files.mapNotNull { (uriStr, fname) ->
                                                try {
                                                    val u = Uri.parse(uriStr)
                                                    Document(name = fname, uri = u, initialText = readFile(u))
                                                } catch (e: Exception) { null }
                                            }
                                            if (parts.isNotEmpty()) {
                                                val pid = try { java.util.UUID.fromString(sp.id) } catch (e: Exception) { java.util.UUID.randomUUID() }
                                                val proj = Project(id = pid, name = sp.name, parts = parts)
                                                tabs.add(proj)
                                                selectedIndex = tabs.size - 1
                                                isPreviewMode = false
                                            }
                                        }
                                        showOpenProjectDialog = false
                                    }.padding(vertical = 8.dp, horizontal = 4.dp)
                                ) {
                                    Text(sp.name, color = gruvboxText, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text("${sp.files.size} arquivo(s)", color = gruvboxGray, fontSize = 12.sp)
                                }
                                IconButton(onClick = {
                                    ProjectStore.delete(context, sp.id)
                                    savedProjects = ProjectStore.load(context)
                                }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Delete, "Excluir projeto", tint = gruvboxGray)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOpenProjectDialog = false }) { Text("Fechar", color = gruvboxOrange) }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = gruvboxBg, drawerContentColor = gruvboxText, modifier = Modifier.width(300.dp)) {
                Text("Índice", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall, color = gruvboxOrange, fontWeight = FontWeight.Bold)
                HorizontalDivider(color = gruvboxSurface)
                val indexProject = currentProject
                if (indexProject != null) {
                    // Projeto: cada arquivo é um capítulo de topo; seus títulos ficam aninhados.
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        indexProject.parts.forEachIndexed { partIndex, part ->
                            item(key = "part-${part.id}") {
                                val active = partIndex == indexProject.activeIndex
                                Box(modifier = Modifier.fillMaxWidth().clickable {
                                    if (isPreviewMode) {
                                        jumpToIndex = indexProject.partStartOffset(partIndex)
                                    } else {
                                        indexProject.activeIndex = partIndex
                                        part.textState = part.textState.copy(selection = TextRange(0))
                                    }
                                    coroutineScope.launch { drawerState.close(); if (!isPreviewMode) focusRequester.requestFocus() }
                                }.background(if (active) gruvboxSurface else Color.Transparent).padding(vertical = 12.dp, horizontal = 12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Description, null, tint = gruvboxOrange, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(part.name, color = gruvboxOrange, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                    }
                                }
                            }
                            val subItems = extractToc(part.textState.text)
                            items(subItems, key = { "sub-${part.id}-${it.index}" }) { item ->
                                Box(modifier = Modifier.fillMaxWidth().clickable {
                                    if (isPreviewMode) {
                                        jumpToIndex = indexProject.partStartOffset(partIndex) + item.index
                                    } else {
                                        indexProject.activeIndex = partIndex
                                        part.textState = part.textState.copy(selection = TextRange(item.index.coerceIn(0, part.textState.text.length)))
                                    }
                                    coroutineScope.launch { drawerState.close(); if (!isPreviewMode) focusRequester.requestFocus() }
                                }.padding(vertical = 10.dp, horizontal = 16.dp).padding(start = (12 + (item.level - 1) * 16).dp)) {
                                    Text(item.label, color = gruvboxText, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                }
                            }
                        }
                    }
                } else if (tocItems.isEmpty()) {
                    Text("Nenhum capítulo encontrado.", modifier = Modifier.padding(16.dp), color = gruvboxGray)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                        // Ícones fixos, disponíveis nos dois modos (edição e visualização)
                        IconButton(onClick = { currentDoc.undoRedoManager.undo { currentDoc.textState = it } }, enabled = currentDoc.undoRedoManager.canUndo) {
                            Icon(Icons.Default.Undo, "Desfazer", tint = if (currentDoc.undoRedoManager.canUndo) gruvboxText else gruvboxGray)
                        }
                        IconButton(onClick = { currentDoc.undoRedoManager.redo { currentDoc.textState = it } }, enabled = currentDoc.undoRedoManager.canRedo) {
                            Icon(Icons.Default.Redo, "Refazer", tint = if (currentDoc.undoRedoManager.canRedo) gruvboxText else gruvboxGray)
                        }
                        IconButton(onClick = { saveCurrentTab() }) { Icon(Icons.Default.Save, "Salvar", tint = gruvboxText) }
                        IconButton(onClick = { openFileLauncher.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FolderOpen, "Abrir arquivo", tint = gruvboxText) }
                        IconButton(onClick = { openProjectFilesLauncher.launch(arrayOf("*/*")) }) { Icon(Icons.Default.LibraryBooks, "Novo projeto", tint = gruvboxText) }

                        IconButton(onClick = { isPreviewMode = !isPreviewMode }) { Icon(if (isPreviewMode) Icons.Default.Edit else Icons.Default.Visibility, "Alternar", tint = gruvboxText) }

                        Box {
                            IconButton(onClick = { menuPage = "main"; showExportMenu = true }) {
                                Icon(Icons.Default.MoreVert, "Menu", tint = gruvboxText)
                            }
                            DropdownMenu(
                                expanded = showExportMenu,
                                onDismissRequest = { showExportMenu = false },
                                modifier = Modifier.background(gruvboxSurface)
                            ) {
                                when (menuPage) {
                                    // ======== Submenu: Temas ========
                                    "themes" -> {
                                        DropdownMenuItem(
                                            text = { Text("Temas", color = gruvboxOrange, fontWeight = FontWeight.Bold) },
                                            leadingIcon = { Icon(Icons.Default.ChevronLeft, "Voltar", tint = gruvboxOrange) },
                                            onClick = { menuPage = "main" }
                                        )
                                        HorizontalDivider(color = gruvboxBg)
                                        appPalettes.forEach { palette ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        palette.name,
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

                                    // ======== Submenu: Recentes ========
                                    "recents" -> {
                                        DropdownMenuItem(
                                            text = { Text("Recentes", color = gruvboxOrange, fontWeight = FontWeight.Bold) },
                                            leadingIcon = { Icon(Icons.Default.ChevronLeft, "Voltar", tint = gruvboxOrange) },
                                            onClick = { menuPage = "main" }
                                        )
                                        HorizontalDivider(color = gruvboxBg)
                                        if (recents.isEmpty()) {
                                            DropdownMenuItem(
                                                text = { Text("Nenhum arquivo recente", color = gruvboxGray) },
                                                enabled = false,
                                                onClick = { }
                                            )
                                        } else {
                                            recents.forEach { rf ->
                                                DropdownMenuItem(
                                                    text = { Text(rf.name, color = gruvboxText, maxLines = 1) },
                                                    onClick = {
                                                        showExportMenu = false
                                                        openDocumentUri(Uri.parse(rf.uri))
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    // ======== Página principal ========
                                    else -> {
                                        MenuSection("Arquivo")
                                        DropdownMenuItem(
                                            text = { Text("Novo", color = gruvboxText) },
                                            leadingIcon = { Icon(Icons.Default.Add, null, tint = gruvboxGray) },
                                            onClick = {
                                                showExportMenu = false
                                                tabs.add(SingleTab(Document()))
                                                selectedIndex = tabs.size - 1
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Abrir arquivo", color = gruvboxText) },
                                            leadingIcon = { Icon(Icons.Default.FolderOpen, null, tint = gruvboxGray) },
                                            onClick = {
                                                showExportMenu = false
                                                openFileLauncher.launch(arrayOf("*/*"))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Novo projeto", color = gruvboxText) },
                                            leadingIcon = { Icon(Icons.Default.LibraryBooks, null, tint = gruvboxGray) },
                                            onClick = {
                                                showExportMenu = false
                                                openProjectFilesLauncher.launch(arrayOf("*/*"))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Abrir projeto", color = gruvboxText) },
                                            leadingIcon = { Icon(Icons.Default.FolderSpecial, null, tint = gruvboxGray) },
                                            onClick = {
                                                showExportMenu = false
                                                savedProjects = ProjectStore.load(context)
                                                showOpenProjectDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Abrir projeto de arquivo…", color = gruvboxText) },
                                            leadingIcon = { Icon(Icons.Default.FileOpen, null, tint = gruvboxGray) },
                                            onClick = {
                                                showExportMenu = false
                                                openProjectFileLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Salvar projeto em disco…", color = if (currentProject != null) gruvboxText else gruvboxGray) },
                                            leadingIcon = { Icon(Icons.Default.SaveAs, null, tint = gruvboxGray) },
                                            enabled = currentProject != null,
                                            onClick = {
                                                showExportMenu = false
                                                currentProject?.let { proj ->
                                                    projectToSaveFile = proj
                                                    saveProjectFileLauncher.launch(proj.name.replace(Regex("[^A-Za-z0-9-_ ]"), "").ifBlank { "projeto" } + ".mdproj")
                                                }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Recentes", color = gruvboxText) },
                                            leadingIcon = { Icon(Icons.Default.History, null, tint = gruvboxGray) },
                                            trailingIcon = { Icon(Icons.Default.ChevronRight, null, tint = gruvboxGray) },
                                            onClick = { menuPage = "recents" }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Salvar", color = gruvboxText) },
                                            leadingIcon = { Icon(Icons.Default.Save, null, tint = gruvboxGray) },
                                            onClick = {
                                                showExportMenu = false
                                                saveCurrentTab()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Salvar como…", color = gruvboxText) },
                                            leadingIcon = { Icon(Icons.Default.SaveAs, null, tint = gruvboxGray) },
                                            onClick = {
                                                showExportMenu = false
                                                saveAsLauncher.launch(currentDoc.name)
                                            }
                                        )

                                        HorizontalDivider(color = gruvboxBg)
                                        MenuSection("Exportar")
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
                                        MenuSection("Aparência")
                                        DropdownMenuItem(
                                            text = { Text("Tamanho da fonte: ${fontSize.toInt()}", color = gruvboxText) },
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
                                        DropdownMenuItem(
                                            text = { Text("Tema: $currentPaletteName", color = gruvboxText) },
                                            leadingIcon = { Icon(Icons.Default.Palette, null, tint = gruvboxGray) },
                                            trailingIcon = { Icon(Icons.Default.ChevronRight, null, tint = gruvboxGray) },
                                            onClick = { menuPage = "themes" }
                                        )

                                        HorizontalDivider(color = gruvboxBg)
                                        MenuSection("Preferências")
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
                                    }
                                }
                            }
                        }
                    }
                )
            },
            bottomBar = {
                // Envelope com fundo do corpo + padding, pra barra virar um "card" arredondado igual ao corpo
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(gruvboxBg)
                        .navigationBarsPadding() // Proteção contra a barra da Samsung
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
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
                        .clip(RoundedCornerShape(12.dp))
                ) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = safeIndex == index,
                            onClick = { selectedIndex = index },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (tab.dirty) {
                                        Box(modifier = Modifier.size(7.dp).background(gruvboxYellow, CircleShape))
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    // Projetos ganham um ícone para se distinguir de arquivos soltos.
                                    if (tab is Project) {
                                        Icon(Icons.Default.LibraryBooks, "Projeto", tint = if (safeIndex == index) gruvboxOrange else gruvboxGray, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = tab.title,
                                        color = if (safeIndex == index) gruvboxOrange else gruvboxText,
                                        fontWeight = if (safeIndex == index) FontWeight.Bold else FontWeight.Normal
                                    )

                                    if (safeIndex == index) {
                                        IconButton(
                                            onClick = {
                                                newFileName = if (tab is Project) tab.name else tab.title.removeSuffix(".md")
                                                showRenameDialog = tab
                                            },
                                            modifier = Modifier.size(24.dp).padding(start = 4.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, "Renomear", tint = gruvboxOrange, modifier = Modifier.size(14.dp))
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    IconButton(
                                        onClick = {
                                            tabs.removeAt(index)
                                            if (tabs.isEmpty()) {
                                                tabs.add(SingleTab(Document()))
                                            }
                                            selectedIndex = selectedIndex.coerceIn(0, tabs.size - 1)
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
            }
        ) { innerPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding().background(gruvboxBg)) {
                val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

                val previewPane: @Composable () -> Unit = {
                    Surface(modifier = Modifier.fillMaxSize().padding(16.dp), shape = RoundedCornerShape(12.dp), color = gruvboxSurface) {
                        // key() garante estado de scroll/seleção/leitura próprio para cada aba.
                        // Projeto: mostra o texto de todas as partes concatenado (lê como um só).
                        val project = currentProject
                        key(currentTab.id) {
                            if (project != null) {
                                MarkdownPreview(
                                    text = project.combinedText(),
                                    onTextChange = { newText -> project.applyCombinedEdit(newText) },
                                    jumpToIndex = jumpToIndex,
                                    onJumpConsumed = { jumpToIndex = null },
                                    initialScrollIndex = project.previewScrollIndex,
                                    initialScrollOffset = project.previewScrollOffset,
                                    onScrollChanged = { i, o ->
                                        if (project.previewScrollIndex != i) scrollTick++
                                        project.previewScrollIndex = i
                                        project.previewScrollOffset = o
                                    },
                                    startReadingFromOffset = readFromOffset,
                                    onStartReadingConsumed = { readFromOffset = null },
                                    fontSize = fontSize,
                                    documentTitle = project.name,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
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
                }

                val editorPane: @Composable () -> Unit = {
                    Surface(modifier = Modifier.fillMaxSize().padding(16.dp), shape = RoundedCornerShape(12.dp), color = gruvboxSurface) {
                    Column(modifier = Modifier.fillMaxSize()) {
                    // Projeto: cabeçalho mostrando qual arquivo (parte) está sendo editado, com navegação.
                    currentProject?.let { project ->
                        Row(
                            modifier = Modifier.fillMaxWidth().background(gruvboxBg).padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { if (project.activeIndex > 0) project.activeIndex-- },
                                enabled = project.activeIndex > 0,
                                modifier = Modifier.size(32.dp)
                            ) { Icon(Icons.Default.KeyboardArrowUp, "Parte anterior", tint = if (project.activeIndex > 0) gruvboxText else gruvboxGray) }
                            Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                                Text("Parte ${project.activeIndex + 1}/${project.parts.size}", color = gruvboxGray, fontSize = 11.sp)
                                Text(project.editable.name, color = gruvboxOrange, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                            IconButton(
                                onClick = { if (project.activeIndex < project.parts.size - 1) project.activeIndex++ },
                                enabled = project.activeIndex < project.parts.size - 1,
                                modifier = Modifier.size(32.dp)
                            ) { Icon(Icons.Default.KeyboardArrowDown, "Próxima parte", tint = if (project.activeIndex < project.parts.size - 1) gruvboxText else gruvboxGray) }
                            // Gerenciar: adicionar/remover/reordenar arquivos do projeto.
                            IconButton(
                                onClick = {
                                    projectName = project.name
                                    projectDraft = project.parts.mapNotNull { p -> p.uri?.let { it to p.name } }
                                    editingProject = project
                                    showProjectDialog = true
                                },
                                modifier = Modifier.size(32.dp)
                            ) { Icon(Icons.Default.Tune, "Gerenciar projeto", tint = gruvboxOrange) }
                        }
                    }
                    // Nova estrutura: Row fixa contendo a LazyRow rolável
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(gruvboxSurface)
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Botões de formatação (roláveis, ocupando toda a largura)
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
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.background(if (enabled) gruvboxButton else gruvboxButtonDisabled, RoundedCornerShape(8.dp))) {
            Icon(icon, contentDescription = label.ifEmpty { null }, tint = tint)
        }
        if (label.isNotEmpty()) {
            Text(label, fontSize = 9.sp, color = gruvboxGray, maxLines = 1)
        }
    }
}

@Composable
private fun MenuSection(title: String) {
    Text(
        text = title,
        color = gruvboxOrange,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
fun ToolBarButton(text: String, onClick: () -> Unit, label: String = "") {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = onClick, modifier = Modifier.background(gruvboxButton, RoundedCornerShape(8.dp)), contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(text, fontWeight = FontWeight.Bold, color = gruvboxText)
        }
        if (label.isNotEmpty()) {
            Text(label, fontSize = 9.sp, color = gruvboxGray, maxLines = 1)
        }
    }
}