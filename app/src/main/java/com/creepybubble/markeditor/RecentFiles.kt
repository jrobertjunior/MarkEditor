package com.creepybubble.markeditor

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Um arquivo aberto recentemente. */
data class RecentFile(val uri: String, val name: String)

/** Lista de arquivos recentes, guardada como JSON nas preferências. */
object RecentFiles {
    private const val KEY = "recents"
    private const val MAX = 10

    fun load(prefs: SharedPreferences): List<RecentFile> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentFile(o.getString("uri"), o.getString("name"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(prefs: SharedPreferences, uri: String, name: String) {
        val list = load(prefs).toMutableList()
        list.removeAll { it.uri == uri }
        list.add(0, RecentFile(uri, name))
        val trimmed = list.take(MAX)
        val arr = JSONArray()
        trimmed.forEach { arr.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }
}
