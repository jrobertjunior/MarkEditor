package com.creepybubble.markeditor

import android.net.Uri
import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Uma acepção: classe gramatical, idioma e as definições. */
data class DictSense(val partOfSpeech: String, val language: String, val definitions: List<String>)

sealed class DictResult {
    data class Ok(val senses: List<DictSense>) : DictResult()
    object NotFound : DictResult()
    object Error : DictResult()
}

/**
 * Consulta definições no Wiktionary (REST API), que devolve JSON já estruturado
 * agrupado por idioma da palavra. Busca primeiro na edição do idioma do app e,
 * se não achar, tenta a edição em inglês (cobertura maior).
 */
object DictionaryClient {

    suspend fun lookup(word: String, appLang: String): DictResult {
        val primary = fetch(word, appLang.ifBlank { "en" })
        if (primary is DictResult.Ok) return primary
        if (appLang.isNotBlank() && appLang != "en") {
            val fallback = fetch(word, "en")
            if (fallback is DictResult.Ok) return fallback
        }
        return primary
    }

    private suspend fun fetch(word: String, wikiLang: String): DictResult = withContext(Dispatchers.IO) {
        val term = Uri.encode(word.trim())
        if (term.isBlank()) return@withContext DictResult.NotFound
        val url = URL("https://$wikiLang.wiktionary.org/api/rest_v1/page/definition/$term")
        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "MarkEditor (dictionary lookup)")
                setRequestProperty("Accept", "application/json")
            }
            when (conn.responseCode) {
                200 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    parse(body)
                }
                404 -> DictResult.NotFound
                else -> DictResult.Error
            }
        } catch (e: Exception) {
            DictResult.Error
        } finally {
            conn?.disconnect()
        }
    }

    private fun parse(body: String): DictResult {
        return try {
            val json = JSONObject(body)
            val senses = ArrayList<DictSense>()
            for (key in json.keys()) {
                val arr = json.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val pos = o.optString("partOfSpeech", "")
                    val language = o.optString("language", key)
                    val defsArr = o.optJSONArray("definitions") ?: continue
                    val defs = ArrayList<String>()
                    for (j in 0 until defsArr.length()) {
                        val raw = defsArr.getJSONObject(j).optString("definition", "")
                        val clean = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                        if (clean.isNotBlank()) defs.add(clean)
                    }
                    if (defs.isNotEmpty()) senses.add(DictSense(pos, language, defs))
                }
            }
            if (senses.isEmpty()) DictResult.NotFound else DictResult.Ok(senses)
        } catch (e: Exception) {
            DictResult.Error
        }
    }
}
