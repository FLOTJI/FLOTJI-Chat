package ru.flotji.hub

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AiClient {

    fun ask(prompt: String, cb: (String) -> Unit) {
        Thread {
            var answer: String? = null
            try {
                val url = URL("https://text.pollinations.ai/openai")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")

                val messages = JSONArray()
                messages.put(
                    JSONObject()
                        .put("role", "system")
                        .put(
                            "content",
                            "Ты голосовой помощник FLOTJI. Отвечай кратко, одно-два предложения, по-русски."
                        )
                )
                messages.put(JSONObject().put("role", "user").put("content", prompt))

                val body = JSONObject()
                    .put("model", "openai")
                    .put("messages", messages)
                    .toString()

                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.readText() ?: ""

                if (code in 200..299 && text.isNotEmpty()) {
                    answer = JSONObject(text)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .optString("content", "")
                }
            } catch (e: Exception) {
                answer = null
            }

            val result = answer?.trim()
            cb(if (result.isNullOrEmpty()) "Не удалось получить ответ" else result)
        }.start()
    }
}
