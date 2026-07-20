package com.creepybubble.markeditor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Um projeto salvo em disco: nome + lista ordenada de arquivos (uri, nome). */
data class SavedProject(
    val id: String,
    val name: String,
    val files: List<Pair<String, String>> // (uri, nome)
)

/**
 * Guarda os projetos do usuário num JSON interno, separado da sessão. Assim um
 * projeto pode ser reaberto quando quiser (não só via restauração de sessão).
 * Guarda apenas a estrutura (nome + arquivos ordenados); o conteúdo continua
 * vindo dos próprios arquivos .md.
 */
object ProjectStore {
    private const val FILE_NAME = "projects.json"

    fun load(context: Context): List<SavedProject> {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return emptyList()
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val filesArr = o.optJSONArray("files") ?: JSONArray()
                val files = (0 until filesArr.length()).map { j ->
                    val f = filesArr.getJSONObject(j)
                    f.optString("uri", "") to f.optString("name", "arquivo.md")
                }
                SavedProject(
                    id = o.optString("id", ""),
                    name = o.optString("name", "Projeto"),
                    files = files
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(context: Context, list: List<SavedProject>) {
        try {
            val arr = JSONArray()
            list.forEach { p ->
                val filesArr = JSONArray()
                p.files.forEach { (uri, name) ->
                    filesArr.put(JSONObject().apply {
                        put("uri", uri)
                        put("name", name)
                    })
                }
                arr.put(JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("files", filesArr)
                })
            }
            File(context.filesDir, FILE_NAME).writeText(arr.toString())
        } catch (e: Exception) {
            // best effort
        }
    }

    /** Insere ou atualiza um projeto (casado pelo id). */
    fun upsert(context: Context, project: SavedProject) {
        if (project.id.isBlank()) return
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.id == project.id }
        if (idx >= 0) list[idx] = project else list.add(project)
        write(context, list)
    }

    fun delete(context: Context, id: String) {
        write(context, load(context).filterNot { it.id == id })
    }

    /** Serializa um projeto no formato do arquivo .mdproj (JSON legível). */
    fun serialize(project: SavedProject): String {
        val filesArr = JSONArray()
        project.files.forEach { (uri, name) ->
            filesArr.put(JSONObject().apply {
                put("uri", uri)
                put("name", name)
            })
        }
        return JSONObject().apply {
            put("format", "markeditor-project")
            put("version", 1)
            put("id", project.id)
            put("name", project.name)
            put("files", filesArr)
        }.toString(2)
    }

    /** Lê um arquivo .mdproj. Retorna null se o conteúdo não for um projeto válido. */
    fun parse(text: String): SavedProject? {
        return try {
            val o = JSONObject(text)
            if (o.optString("format") != "markeditor-project") return null
            val filesArr = o.optJSONArray("files") ?: JSONArray()
            val files = (0 until filesArr.length()).map { j ->
                val f = filesArr.getJSONObject(j)
                f.optString("uri", "") to f.optString("name", "arquivo.md")
            }
            SavedProject(
                id = o.optString("id", ""),
                name = o.optString("name", "Projeto"),
                files = files
            )
        } catch (e: Exception) {
            null
        }
    }
}
