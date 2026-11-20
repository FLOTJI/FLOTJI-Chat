package ru.flotji.hub

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Бесплатный клиент к текстовой модели без ключа.
 * Если сервис недоступен — бросает исключение, вызывающий код отвечает заглушкой.
 */
object AiClient {

    private const val SYS =
        "Ты голосовой помощник FLOTJI внутри приложения Flotji Chat. " +
            "Отвечай по-русски, кратко: одно-два предложения. Без списков, без markdown, без эмодзи."

    fun ask(question: String): String {
        val q = URLEncoder.encode(question, "UTF-8")
        val s = URLEncoder.encode(SYS, "UTF-8")
        val variants = arrayOf(
            "https://text.pollinations.ai/$q?model=openai&system=$s",
            "https://text.pollinations.ai/$q?system=$s"
        )
        for (u in variants) {
            try {
                val t = httpGet(u).trim()
                if (t.length > 1 && !t.startsWith("{") && !t.contains("\"error\"")) return t
            } catch (e: Exception) {
                // пробуем следующий вариант
            }
        }
        throw IllegalStateException("no answer")
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", "FlotjiHub/2.2")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IllegalStateException("http $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
