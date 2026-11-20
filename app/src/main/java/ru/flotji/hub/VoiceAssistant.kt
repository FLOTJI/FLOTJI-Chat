package ru.flotji.hub

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Голосовой помощник FLOTJI.
 * Слушает в цикле, ловит имя «флотжи» и дальше исполняет команду или спрашивает ИИ.
 */
class VoiceAssistant(
    private val act: Activity,
    private val onState: (String) -> Unit,
    private val onAnswer: (String) -> Unit,
    private val onCommand: (String) -> String?
) {

    private var rec: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var running = false
    private var awaiting = false
    private var busy = false

    private val h = Handler(Looper.getMainLooper())

    private val wake = listOf(
        "флотжи", "флоджи", "флотджи", "флочи", "плотжи", "влотжи", "flotji"
    )

    fun init() {
        tts = TextToSpeech(act) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("ru", "RU")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(act)

    fun start() {
        if (running) return
        if (!SpeechRecognizer.isRecognitionAvailable(act)) {
            onState("Распознавание речи недоступно")
            return
        }
        running = true
        create()
        h.postDelayed({ listen() }, 300)
    }

    fun stop() {
        running = false
        awaiting = false
        busy = false
        try {
            rec?.cancel()
        } catch (e: Exception) {
            // ignore
        }
        onState("")
    }

    fun shutdown() {
        stop()
        try {
            rec?.destroy()
        } catch (e: Exception) {
            // ignore
        }
        rec = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            // ignore
        }
        tts = null
    }

    fun isRunning(): Boolean = running

    fun test() {
        speak("Привет, я FLOTJI. Скажи: Флотжи, который час.")
    }

    fun speak(text: String) {
        val t = tts ?: return
        if (!ttsReady) return
        val clean = text.replace(Regex("[*_`#>]"), "").take(400)
        try {
            t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "flotji")
        } catch (e: Exception) {
            // ignore
        }
    }

    // ---------- распознавание ----------

    private fun create() {
        try {
            rec?.destroy()
        } catch (e: Exception) {
            // ignore
        }
        rec = SpeechRecognizer.createSpeechRecognizer(act)
        rec?.setRecognitionListener(listener)
    }

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, act.packageName)
    }

    private fun listen() {
        if (!running) return
        if (busy) return
        busy = true
        onState("FLOTJI слушает")
        try {
            rec?.startListening(intent())
        } catch (e: Exception) {
            busy = false
            create()
            h.postDelayed({ listen() }, 1200)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            onState("Говорите…")
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            busy = false
            if (!running) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    running = false
                    onState("Нет доступа к микрофону")
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    create()
                    h.postDelayed({ listen() }, 900)
                }
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> h.postDelayed({ listen() }, 250)
                else -> h.postDelayed({ listen() }, 700)
            }
        }

        override fun onResults(results: Bundle?) {
            busy = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) {
                h.postDelayed({ listen() }, 250)
            } else {
                handle(text)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank() && containsWake(text) && !awaiting) onState("Да, слушаю…")
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun containsWake(s: String): Boolean {
        val low = s.lowercase(Locale.ROOT)
        return wake.any { low.contains(it) }
    }

    private fun handle(text: String) {
        val low = text.lowercase(Locale.ROOT)
        val hasWake = containsWake(low)

        if (!hasWake && !awaiting) {
            h.postDelayed({ listen() }, 200)
            return
        }

        var cmd = low
        for (w in wake) cmd = cmd.replace(w, " ")
        cmd = cmd.replace(Regex("[,.!?;:—–-]"), " ").replace(Regex("\\s+"), " ").trim()

        if (cmd.length < 2) {
            awaiting = true
            onState("Слушаю…")
            speak("Да, слушаю")
            h.postDelayed({ listen() }, 700)
            return
        }

        awaiting = false
        onState("Думаю…")

        val local = try {
            onCommand(cmd)
        } catch (e: Exception) {
            null
        }

        if (local != null) {
            onAnswer(local)
            speak(local)
            h.postDelayed({ listen() }, delayFor(local))
        } else {
            askAi(cmd)
        }
    }

    private fun askAi(question: String) {
        onState("Ищу ответ…")
        Thread {
            val answer = try {
                AiClient.ask(question)
            } catch (e: Exception) {
                ""
            }
            h.post {
                if (answer.isBlank()) {
                    val msg = "Не смог получить ответ. Проверьте интернет."
                    onAnswer(msg)
                    speak(msg)
                    h.postDelayed({ listen() }, 1500)
                } else {
                    onAnswer(answer)
                    speak(answer)
                    h.postDelayed({ listen() }, delayFor(answer))
                }
            }
        }.start()
    }

    private fun delayFor(text: String): Long = Math.max(1200L, text.length * 55L)
}
