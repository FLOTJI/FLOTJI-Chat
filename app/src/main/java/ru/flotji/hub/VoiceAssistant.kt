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

class VoiceAssistant(
    private val activity: Activity,
    private val onStatus: (String) -> Unit,
    private val onAnswer: (String) -> Unit
) : RecognitionListener {

    private val ui = Handler(Looper.getMainLooper())
    private val device = DeviceControl(activity)

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var active = false

    val isRunning: Boolean
        get() = active

    init {
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity)
            recognizer?.setRecognitionListener(this)
        } catch (e: Exception) {
            recognizer = null
        }
        try {
            tts = TextToSpeech(activity) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        tts?.language = Locale("ru", "RU")
                        ttsReady = true
                    } catch (e: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            tts = null
        }
    }

    fun toggle() {
        if (active) stop() else start()
    }

    fun start() {
        if (active) return
        active = true
        onStatus("🎤 слушаю")
        listen()
    }

    fun stop() {
        active = false
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
        }
        try {
            tts?.stop()
        } catch (e: Exception) {
        }
        onStatus("🎤")
    }

    fun shutdown() {
        active = false
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        recognizer = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
        }
        tts = null
    }

    private fun listen() {
        if (!active) return
        onStatus("🎤 говорите")
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try {
            recognizer?.startListening(i)
        } catch (e: Exception) {
            onStatus("🎤")
        }
    }

    private fun restartLater(delay: Long) {
        if (!active) return
        ui.postDelayed({ listen() }, delay)
    }

    private fun handle(raw: String) {
        onStatus("🎤 думаю")
        val low = raw.lowercase(Locale("ru", "RU"))
        val wake = listOf(
            "флотжи", "флоджи", "флотджи", "плотжи", "плоти",
            "флот жи", "flotji", "flotgy"
        )
        val found = wake.firstOrNull { low.contains(it) }
        if (found == null) {
            onStatus("🎤 слушаю")
            restartLater(400)
            return
        }

        var cmd = low.substringAfter(found).trim()
        cmd = cmd.trim(',', '.', '!', '?', ' ', '-')

        if (cmd.isEmpty()) {
            say("Да, слушаю")
            return
        }

        val local = device.handle(cmd)
        if (local != null) {
            say(local)
            return
        }

        AiClient.ask(cmd) { ans -> ui.post { say(ans) } }
    }

    private fun say(text: String) {
        onAnswer(text)
        if (ttsReady) {
            try {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "flotji")
            } catch (e: Exception) {
            }
        }
        val wait = (text.length * 70L + 1200L).coerceAtMost(15000L)
        ui.postDelayed({
            onStatus("🎤 слушаю")
            restartLater(300)
        }, wait)
    }

    override fun onResults(results: Bundle?) {
        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = list?.firstOrNull()?.trim().orEmpty()
        if (text.isEmpty()) {
            restartLater(600)
            return
        }
        handle(text)
    }

    override fun onError(error: Int) {
        if (!active) return
        onStatus("🎤 слушаю")
        restartLater(700)
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
