package com.creepybubble.markeditor

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Aplica o idioma escolhido pelo usuário sobre o Context base da Activity.
 * Se nenhum idioma foi escolhido ("" = padrão do sistema), não mexe em nada e o
 * Android carrega os recursos que combinam com o idioma do aparelho.
 */
object LocaleHelper {
    const val PREFS = "app_prefs"
    const val KEY = "app_language"

    /** Idioma salvo ("" quando é o padrão do sistema). */
    fun savedLanguage(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    /** Envolve o context com o locale escolhido (ou devolve o mesmo, se for o do sistema). */
    fun wrap(context: Context): Context {
        val lang = savedLanguage(context)
        if (lang.isEmpty()) return context
        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
