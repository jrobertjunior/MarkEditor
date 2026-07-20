package com.creepybubble.markeditor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Estado de um arquivo (parte) a ser persistido. */
data class DocSnapshot(
    val name: String,
    val uri: String?,
    val text: String,
    val selStart: Int,
    val selEnd: Int,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0
)

/**
 * Estado de uma aba: "single" = um arquivo; "project" = vários arquivos
 * (parts) apresentados como um documento só.
 */
data class TabSnapshot(
    val type: String,               // "single" | "project"
    val name: String,               // nome do projeto (ignorado em "single")
    val activeIndex: Int,           // parte ativa (projetos)
    val parts: List<DocSnapshot>,   // em "single", contém exatamente um item
    val id: String = ""             // id estável do projeto (casa com o ProjectStore)
)

/** Estado da sessão inteira (todas as abas + qual estava ativa). */
data class SessionSnapshot(
    val selectedIndex: Int,
    val previewMode: Boolean,
    val tabs: List<TabSnapshot>
)

/**
 * Guarda e recupera a sessão em um arquivo JSON no armazenamento interno do app.
 * Assim as abas abertas (arquivos e projetos), o texto (mesmo não salvo), a aba
 * ativa e a posição do cursor voltam ao reabrir o app.
 */
object SessionStore {
    private const val FILE_NAME = "session.json"

    private fun docToJson(d: DocSnapshot): JSONObject = JSONObject().apply {
        put("name", d.name)
        put("uri", d.uri ?: JSONObject.NULL)
        put("text", d.text)
        put("selStart", d.selStart)
        put("selEnd", d.selEnd)
        put("scrollIndex", d.scrollIndex)
        put("scrollOffset", d.scrollOffset)
    }

    private fun jsonToDoc(o: JSONObject): DocSnapshot = DocSnapshot(
        name = o.optString("name", "Novo Arquivo.md"),
        uri = if (o.isNull("uri")) null else o.optString("uri", null),
        text = o.optString("text", ""),
        selStart = o.optInt("selStart", 0),
        selEnd = o.optInt("selEnd", 0),
        scrollIndex = o.optInt("scrollIndex", 0),
        scrollOffset = o.optInt("scrollOffset", 0)
    )

    fun save(context: Context, session: SessionSnapshot) {
        try {
            val tabsArray = JSONArray()
            session.tabs.forEach { t ->
                val partsArray = JSONArray()
                t.parts.forEach { partsArray.put(docToJson(it)) }
                tabsArray.put(JSONObject().apply {
                    put("type", t.type)
                    put("name", t.name)
                    put("activeIndex", t.activeIndex)
                    put("id", t.id)
                    put("parts", partsArray)
                })
            }
            val root = JSONObject()
            root.put("selectedIndex", session.selectedIndex)
            root.put("previewMode", session.previewMode)
            root.put("tabs", tabsArray)
            File(context.filesDir, FILE_NAME).writeText(root.toString())
        } catch (e: Exception) {
            // Persistência é "best effort"; se falhar, apenas seguimos sem quebrar o app.
        }
    }

    fun load(context: Context): SessionSnapshot? {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return null
            val root = JSONObject(file.readText())

            val tabs: List<TabSnapshot> = when {
                // Formato novo: abas (arquivos e projetos).
                root.has("tabs") -> {
                    val arr = root.getJSONArray("tabs")
                    (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        val partsArr = o.optJSONArray("parts") ?: JSONArray()
                        val parts = (0 until partsArr.length()).map { j -> jsonToDoc(partsArr.getJSONObject(j)) }
                        TabSnapshot(
                            type = o.optString("type", "single"),
                            name = o.optString("name", "Projeto"),
                            activeIndex = o.optInt("activeIndex", 0),
                            parts = parts.ifEmpty { listOf(jsonToDoc(o)) },
                            id = o.optString("id", "")
                        )
                    }
                }
                // Formato antigo: lista simples de arquivos ("docs").
                root.has("docs") -> {
                    val arr = root.getJSONArray("docs")
                    (0 until arr.length()).map { i ->
                        val d = jsonToDoc(arr.getJSONObject(i))
                        TabSnapshot("single", d.name, 0, listOf(d))
                    }
                }
                else -> emptyList()
            }
            if (tabs.isEmpty()) return null

            SessionSnapshot(
                selectedIndex = root.optInt("selectedIndex", 0),
                previewMode = root.optBoolean("previewMode", false),
                tabs = tabs
            )
        } catch (e: Exception) {
            null
        }
    }
}
