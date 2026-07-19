package com.creepybubble.markeditor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Estado de uma aba a ser persistido. */
data class DocSnapshot(
    val name: String,
    val uri: String?,
    val text: String,
    val selStart: Int,
    val selEnd: Int,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0
)

/** Estado da sessão inteira (todas as abas + qual estava ativa). */
data class SessionSnapshot(
    val selectedIndex: Int,
    val previewMode: Boolean,
    val docs: List<DocSnapshot>
)

/**
 * Guarda e recupera a sessão em um arquivo JSON no armazenamento interno do app.
 * Assim os arquivos abertos, o texto (mesmo não salvo), a aba ativa e a posição do
 * cursor voltam ao reabrir o app.
 */
object SessionStore {
    private const val FILE_NAME = "session.json"

    fun save(context: Context, session: SessionSnapshot) {
        try {
            val docsArray = JSONArray()
            session.docs.forEach { d ->
                val o = JSONObject()
                o.put("name", d.name)
                o.put("uri", d.uri ?: JSONObject.NULL)
                o.put("text", d.text)
                o.put("selStart", d.selStart)
                o.put("selEnd", d.selEnd)
                o.put("scrollIndex", d.scrollIndex)
                o.put("scrollOffset", d.scrollOffset)
                docsArray.put(o)
            }
            val root = JSONObject()
            root.put("selectedIndex", session.selectedIndex)
            root.put("previewMode", session.previewMode)
            root.put("docs", docsArray)
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
            val docsArray = root.getJSONArray("docs")
            if (docsArray.length() == 0) return null

            val docs = (0 until docsArray.length()).map { i ->
                val o = docsArray.getJSONObject(i)
                DocSnapshot(
                    name = o.optString("name", "Novo Arquivo.md"),
                    uri = if (o.isNull("uri")) null else o.optString("uri", null),
                    text = o.optString("text", ""),
                    selStart = o.optInt("selStart", 0),
                    selEnd = o.optInt("selEnd", 0),
                    scrollIndex = o.optInt("scrollIndex", 0),
                    scrollOffset = o.optInt("scrollOffset", 0)
                )
            }
            SessionSnapshot(
                selectedIndex = root.optInt("selectedIndex", 0),
                previewMode = root.optBoolean("previewMode", false),
                docs = docs
            )
        } catch (e: Exception) {
            null
        }
    }
}
