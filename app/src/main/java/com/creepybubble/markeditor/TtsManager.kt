package com.creepybubble.markeditor

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** Um motor de TTS instalado no aparelho (Google, Samsung, etc.). */
data class TtsEngineOption(val packageName: String, val label: String)

/** Uma voz disponível dentro do motor selecionado. */
data class TtsVoiceOption(val name: String, val label: String, val subtitle: String)

/** Um idioma disponível entre as vozes (para filtrar a lista). */
data class TtsLanguageOption(val code: String, val label: String)

/** Frase de exemplo para o botão "testar voz", por código de idioma. */
private val sampleByLanguage = mapOf(
    "pt" to "Este é um exemplo da voz selecionada.",
    "en" to "This is an example of the selected voice.",
    "es" to "Este es un ejemplo de la voz seleccionada.",
    "fr" to "Ceci est un exemple de la voix sélectionnée.",
    "de" to "Dies ist ein Beispiel der ausgewählten Stimme.",
    "it" to "Questo è un esempio della voce selezionata.",
    "nl" to "Dit is een voorbeeld van de geselecteerde stem.",
    "ru" to "Это пример выбранного голоса.",
    "ja" to "これは選択した音声の例です。",
    "ko" to "선택한 음성의 예입니다.",
    "zh" to "这是所选语音的示例。"
)

/**
 * Remove a sintaxe do markdown para que o TTS leia só o texto "de verdade".
 * Blocos de código cercados por ``` são descartados (ninguém quer ouvir código).
 */
fun stripMarkdown(input: String): String {
    var s = input

    // Blocos de código cercados: fora.
    s = s.replace(Regex("(?s)```.*?```"), " ")

    // Imagens: mantém apenas o texto alternativo. ![alt](url) -> alt
    s = s.replace(Regex("!\\[(.*?)]\\(.*?\\)"), "$1")
    // Links: mantém apenas o rótulo. [texto](url) -> texto
    s = s.replace(Regex("\\[(.*?)]\\(.*?\\)"), "$1")

    // Código inline, negrito, itálico e riscado: tira os marcadores.
    s = s.replace(Regex("`([^`]*)`"), "$1")
    s = s.replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
    s = s.replace(Regex("__(.*?)__"), "$1")
    s = s.replace(Regex("\\*(.*?)\\*"), "$1")
    s = s.replace(Regex("(?<!\\w)_(.*?)_(?!\\w)"), "$1")
    s = s.replace(Regex("~~(.*?)~~"), "$1")

    // Prefixos de bloco, linha a linha.
    s = s.lines().joinToString("\n") { line ->
        var l = line
        l = l.replace(Regex("^\\s{0,3}#{1,6}\\s*"), "")     // títulos
        l = l.replace(Regex("^\\s{0,3}>\\s?"), "")           // citações
        l = l.replace(Regex("^\\s*[-*+]\\s+"), "")           // listas
        l = l.replace(Regex("^\\s*\\d+[.)]\\s+"), "")        // listas numeradas
        l = l.replace(Regex("^\\s*\\|"), "").replace("|", " ") // tabelas
        l = l.replace(Regex("^\\s*[-=*_]{3,}\\s*$"), "")     // linhas horizontais
        l
    }

    // Colapsa espaços sobrando.
    s = s.replace(Regex("[ \\t]+"), " ").trim()
    return s
}

/**
 * Envolve o TextToSpeech nativo do Android. Permite escolher o motor (Google, Samsung…)
 * e a voz, além de controlar a leitura (ler, pausar, pular, parar). As escolhas de
 * motor/voz ficam salvas em SharedPreferences e sobrevivem ao fechar o app.
 */
class TtsManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("tts_prefs", Context.MODE_PRIVATE)

    private var tts: TextToSpeech? = null
    private var ready = false
    private var lastIndex = -1
    private var blocks: List<String> = emptyList()
    // Marca as interrupções que nós mesmos provocamos (pausar/pular/parar),
    // para não confundir com um erro real de síntese.
    private var manualInterrupt = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /** true enquanto o motor está de fato falando. */
    var isSpeaking by mutableStateOf(false)
        private set

    /** true quando a leitura foi pausada (dá para retomar). */
    var isPaused by mutableStateOf(false)
        private set

    /** Índice do bloco que está sendo lido agora, ou -1. */
    var currentIndex by mutableIntStateOf(-1)
        private set

    /** Início/fim (no texto falado do bloco atual) da palavra sendo lida, ou -1. */
    var currentWordStart by mutableIntStateOf(-1)
        private set
    var currentWordEnd by mutableIntStateOf(-1)
        private set

    /** Motores de TTS instalados no aparelho. */
    var engineOptions by mutableStateOf<List<TtsEngineOption>>(emptyList())
        private set

    /** Vozes disponíveis no motor selecionado (já filtradas por idioma). */
    var voiceOptions by mutableStateOf<List<TtsVoiceOption>>(emptyList())
        private set

    /** Idiomas disponíveis entre as vozes do motor atual. */
    var voiceLanguages by mutableStateOf<List<TtsLanguageOption>>(emptyList())
        private set

    /** Código do idioma filtrado (ex.: "pt", "en") ou null para mostrar todos. */
    var selectedLanguage by mutableStateOf<String?>(null)
        private set

    // Todas as vozes do motor, antes do filtro de idioma.
    private var allVoices: List<Voice> = emptyList()

    /** Pacote do motor atualmente em uso. */
    var selectedEngine by mutableStateOf<String?>(null)
        private set

    /** Nome da voz atualmente em uso. */
    var selectedVoice by mutableStateOf<String?>(null)
        private set

    /** Velocidade da fala (1.0 = normal). */
    var speechRate by mutableFloatStateOf(1.0f)
        private set

    /** Tom da fala (1.0 = normal). */
    var pitch by mutableFloatStateOf(1.0f)
        private set

    /** Minutos do timer de soneca (0 = desligado). */
    var sleepTimerMinutes by mutableIntStateOf(0)
        private set

    private var sleepRunnable: Runnable? = null

    init {
        speechRate = prefs.getFloat("rate", 1.0f)
        pitch = prefs.getFloat("pitch", 1.0f)
        // Começa com o motor salvo (ou o padrão do sistema, se não houver).
        initEngine(prefs.getString("engine", null))
    }

    // ---- Inicialização / troca de motor ------------------------------------

    private fun initEngine(engineName: String?) {
        tts?.stop()
        tts?.shutdown()
        ready = false

        val listener = TextToSpeech.OnInitListener { status -> onEngineReady(status, engineName) }
        tts = if (engineName != null) {
            TextToSpeech(appContext, listener, engineName)
        } else {
            TextToSpeech(appContext, listener)
        }
    }

    private fun onEngineReady(status: Int, requestedEngine: String?) {
        if (status != TextToSpeech.SUCCESS) return
        val engine = tts ?: return
        ready = true

        // Tenta português do Brasil; se faltar o pacote de voz, usa o idioma do aparelho.
        val ptBr = Locale("pt", "BR")
        val res = engine.setLanguage(ptBr)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.getDefault())
        }
        engine.setSpeechRate(speechRate)
        engine.setPitch(pitch)

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val i = utteranceId?.toIntOrNull() ?: return
                mainHandler.post {
                    manualInterrupt = false
                    currentIndex = i
                    currentWordStart = -1
                    currentWordEnd = -1
                    isSpeaking = true
                    isPaused = false
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                mainHandler.post {
                    currentWordStart = start
                    currentWordEnd = end
                }
            }

            override fun onDone(utteranceId: String?) {
                val i = utteranceId?.toIntOrNull() ?: return
                if (i >= lastIndex) mainHandler.post {
                    if (!manualInterrupt && !isPaused) resetState()
                }
            }

            @Deprecated("Assinatura antiga, mantida por compatibilidade")
            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    if (!manualInterrupt && !isPaused) resetState()
                }
            }
        })

        mainHandler.post {
            engineOptions = try {
                engine.engines.map { TtsEngineOption(it.name, it.label) }
                    .sortedBy { it.label.lowercase() }
            } catch (e: Exception) {
                emptyList()
            }
            selectedEngine = requestedEngine ?: engine.defaultEngine

            reloadVoices()

            // Reaplica a voz salva se ela existir neste motor; senão, mostra a padrão.
            val savedVoice = prefs.getString("voice", null)
            if (savedVoice != null && allVoices.any { it.name == savedVoice }) {
                applyVoice(savedVoice)
                applyVoiceFilter()
            } else {
                selectedVoice = try {
                    (engine.voice ?: engine.defaultVoice)?.name
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    private fun reloadVoices() {
        val engine = tts ?: return
        allVoices = try {
            (engine.voices ?: emptySet()).toList()
        } catch (e: Exception) {
            emptyList()
        }

        // Lista de idiomas disponíveis, com pt / idioma do aparelho / inglês na frente.
        val langs = allVoices.map { it.locale.language }.filter { it.isNotBlank() }.distinct()
        val priority = listOf("pt", Locale.getDefault().language, "en")
        voiceLanguages = langs
            .sortedWith(
                compareBy(
                    { priority.indexOf(it).let { p -> if (p < 0) Int.MAX_VALUE else p } },
                    { displayLanguage(it) }
                )
            )
            .map { TtsLanguageOption(it, displayLanguage(it)) }

        // Define um filtro padrão sensato se o atual não existir mais.
        if (selectedLanguage != null && langs.none { it == selectedLanguage }) {
            selectedLanguage = null
        }
        if (selectedLanguage == null) {
            selectedLanguage = when {
                langs.contains("pt") -> "pt"
                langs.contains(Locale.getDefault().language) -> Locale.getDefault().language
                else -> null
            }
        }

        applyVoiceFilter()
    }

    private fun applyVoiceFilter() {
        val filtered = if (selectedLanguage == null) {
            allVoices
        } else {
            allVoices.filter { it.locale.language == selectedLanguage }
        }

        voiceOptions = filtered
            .sortedWith(compareBy({ it.locale.toString() }, { -it.quality }, { it.name }))
            .map { v ->
                val net = if (v.isNetworkConnectionRequired) " • online" else ""
                val q = when {
                    v.quality >= Voice.QUALITY_VERY_HIGH -> "qualidade alta"
                    v.quality >= Voice.QUALITY_HIGH -> "qualidade boa"
                    else -> "qualidade padrão"
                }
                TtsVoiceOption(
                    name = v.name,
                    label = "${v.locale} • $q$net",
                    subtitle = v.name
                )
            }
    }

    /** Troca o filtro de idioma da lista de vozes (null = todos). */
    fun setLanguageFilter(code: String?) {
        selectedLanguage = code
        applyVoiceFilter()
    }

    private fun displayLanguage(code: String): String {
        val name = Locale(code).getDisplayLanguage(Locale.getDefault())
        val label = if (name.isBlank()) code else name
        return label.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    // ---- Seleção pública ---------------------------------------------------

    fun selectEngine(packageName: String) {
        if (packageName == selectedEngine) return
        stop()
        prefs.edit().putString("engine", packageName).apply()
        selectedVoice = null
        voiceOptions = emptyList()
        initEngine(packageName)
    }

    fun selectVoice(name: String) {
        applyVoice(name)
        prefs.edit().putString("voice", name).apply()
    }

    private fun applyVoice(name: String) {
        val engine = tts ?: return
        val voice = try {
            engine.voices?.firstOrNull { it.name == name }
        } catch (e: Exception) {
            null
        } ?: return
        engine.voice = voice
        selectedVoice = name
        selectedLanguage = voice.locale.language
    }

    // ---- Controle de leitura ----------------------------------------------

    fun speak(blocks: List<String>, startAt: Int = 0) {
        this.blocks = blocks
        speakFrom(startAt)
    }

    /** Texto "falado" (sem markdown) do bloco em [index]. */
    fun spokenText(index: Int): String =
        blocks.getOrNull(index)?.let { stripMarkdown(it) } ?: ""

    fun updateSpeechRate(value: Float) {
        speechRate = value.coerceIn(0.5f, 2.5f)
        tts?.setSpeechRate(speechRate)
        prefs.edit().putFloat("rate", speechRate).apply()
    }

    fun updatePitch(value: Float) {
        pitch = value.coerceIn(0.5f, 2.0f)
        tts?.setPitch(pitch)
        prefs.edit().putFloat("pitch", pitch).apply()
    }

    /** Fala uma frase de exemplo no idioma da voz atual (não altera o estado de leitura). */
    fun previewVoice() {
        val engine = tts ?: return
        if (!ready) return
        engine.setSpeechRate(speechRate)
        engine.setPitch(pitch)
        val lang = currentVoiceLanguage()
        val sample = sampleByLanguage[lang] ?: sampleByLanguage["en"]!!
        engine.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    /** Idioma da voz em uso (cai no filtro ou em pt se não der para determinar). */
    private fun currentVoiceLanguage(): String {
        val fromVoice = allVoices.firstOrNull { it.name == selectedVoice }?.locale?.language
        return fromVoice ?: selectedLanguage ?: "pt"
    }

    /** Agenda a parada automática da leitura após [minutes] minutos (0 = desliga). */
    fun setSleepTimer(minutes: Int) {
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepTimerMinutes = minutes
        if (minutes <= 0) {
            sleepRunnable = null
            return
        }
        val r = Runnable {
            stop()
            sleepTimerMinutes = 0
            sleepRunnable = null
        }
        sleepRunnable = r
        mainHandler.postDelayed(r, minutes * 60_000L)
    }

    private fun speakFrom(index: Int) {
        val engine = tts ?: return
        if (!ready || blocks.isEmpty()) return

        manualInterrupt = true
        engine.stop()
        lastIndex = -1
        var firstQueued = true

        for (i in index.coerceAtLeast(0) until blocks.size) {
            val clean = stripMarkdown(blocks[i])
            if (clean.isNotBlank()) {
                val mode = if (firstQueued) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                engine.speak(clean, mode, null, i.toString())
                firstQueued = false
                lastIndex = i
            }
        }

        if (lastIndex >= 0) {
            isSpeaking = true
            isPaused = false
        } else {
            resetState()
        }
    }

    fun pause() {
        manualInterrupt = true
        tts?.stop()
        isSpeaking = false
        isPaused = true
    }

    fun resume() {
        speakFrom(if (currentIndex >= 0) currentIndex else 0)
    }

    fun next() {
        val target = nextSpeakable(currentIndex + 1)
        if (target >= 0) speakFrom(target) else stop()
    }

    fun previous() {
        val target = prevSpeakable(currentIndex - 1)
        speakFrom(if (target >= 0) target else currentIndex.coerceAtLeast(0))
    }

    fun stop() {
        manualInterrupt = true
        tts?.stop()
        resetState()
    }

    fun shutdown() {
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepRunnable = null
        sleepTimerMinutes = 0
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        resetState()
    }

    private fun nextSpeakable(from: Int): Int {
        var i = from
        while (i in blocks.indices) {
            if (stripMarkdown(blocks[i]).isNotBlank()) return i
            i++
        }
        return -1
    }

    private fun prevSpeakable(from: Int): Int {
        var i = from
        while (i in blocks.indices) {
            if (stripMarkdown(blocks[i]).isNotBlank()) return i
            i--
        }
        return -1
    }

    private fun resetState() {
        isSpeaking = false
        isPaused = false
        currentIndex = -1
        currentWordStart = -1
        currentWordEnd = -1
    }
}

/**
 * Cria um TtsManager amarrado ao ciclo de vida da composição:
 * quando a tela sai (ex.: voltar ao modo de edição), o motor é desligado e a fala para.
 */
@Composable
fun rememberTtsManager(): TtsManager {
    val context = LocalContext.current
    val manager = remember { TtsManager(context) }
    DisposableEffect(Unit) {
        onDispose { manager.shutdown() }
    }
    return manager
}
