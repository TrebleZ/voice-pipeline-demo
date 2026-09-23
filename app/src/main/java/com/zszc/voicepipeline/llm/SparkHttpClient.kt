package com.zszc.voicepipeline.llm

import com.google.gson.JsonParser
import com.zszc.voicepipeline.BuildConfig
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** 星火 HTTP OpenAI 兼容聊天客户端。 */
class SparkHttpClient(
    private val apiPassword: String = BuildConfig.IFLYTEK_SPARK_API_PASSWORD,
    private val model: String = BuildConfig.IFLYTEK_SPARK_MODEL,
    private val endpoint: String = ENDPOINT,
) {
    fun isConfigured(): Boolean = apiPassword.isNotBlank()

    fun chat(
        userText: String,
        history: List<SparkChatMessage> = emptyList(),
    ): Result<String> = runCatching {
        check(isConfigured()) { "星火大模型 APIPassword 未配置" }
        val requestBody = SparkHttpRequestBuilder.build(userText, history, model)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $apiPassword")
        }

        connection.outputStream.use { stream ->
            stream.write(requestBody.toByteArray(StandardCharsets.UTF_8))
        }

        val responseText = if (connection.responseCode in 200..299) {
            connection.inputStream.readUtf8()
        } else {
            val errorText = connection.errorStream?.readUtf8().orEmpty()
            val message = SparkHttpResponseParser.parseErrorMessage(errorText)
                ?: "星火大模型请求失败：HTTP ${connection.responseCode}"
            throw IllegalStateException(message)
        }

        SparkHttpResponseParser.parseAssistantText(responseText).getOrThrow()
    }

    private fun InputStream.readUtf8(): String =
        BufferedReader(InputStreamReader(this, StandardCharsets.UTF_8)).use { it.readText() }

    private companion object {
        const val ENDPOINT = "https://spark-api-open.xf-yun.com/v1/chat/completions"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
    }
}

/** 星火对话历史消息。 */
data class SparkChatMessage(
    val role: String,
    val content: String,
)

/** 构造星火 HTTP 调用请求体，第一版使用非流式回复，便于 TTS 完整播报。 */
object SparkHttpRequestBuilder {
    fun build(
        userText: String,
        history: List<SparkChatMessage>,
        model: String = BuildConfig.IFLYTEK_SPARK_MODEL,
    ): String {
        val escapedHistory = history
            .takeLast(MAX_HISTORY_MESSAGES)
            .joinToString(separator = ",") { message ->
                """{"role":"${message.role.escapeJson()}","content":"${message.content.escapeJson()}"}"""
            }
        val messages = buildString {
            append("""{"role":"system","content":"${SYSTEM_PROMPT.escapeJson()}"}""")
            if (escapedHistory.isNotBlank()) {
                append(',')
                append(escapedHistory)
            }
            append(',')
            append("""{"role":"user","content":"${userText.escapeJson()}"}""")
        }
        return """
            {
              "model": "${model.escapeJson()}",
              "stream": false,
              "temperature": 0.5,
              "max_tokens": 512,
              "messages": [$messages]
            }
        """.trimIndent()
    }

    private fun String.escapeJson(): String =
        buildString {
            this@escapeJson.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }

    private const val MAX_HISTORY_MESSAGES = 8
    private const val SYSTEM_PROMPT =
        "你是桌面机器人语音助手小飞。回答要简短、自然、适合 TTS 播报，通常不超过 80 个中文字。" +
            "你可以陪用户聊天、解释问题、给出建议。涉及打开应用、系统设置、天气等设备动作时，说明你会尽力处理；不要编造已经执行了本地代码没有执行的动作。"
}

/** 解析星火 OpenAI 兼容响应。 */
object SparkHttpResponseParser {
    fun parseAssistantText(json: String): Result<String> = runCatching {
        parseErrorMessage(json)?.let { throw IllegalStateException(it) }
        val root = JsonParser.parseString(json).asJsonObject
        val choices = root.getAsJsonArray("choices")
        val firstChoice = choices?.firstOrNull()?.asJsonObject
            ?: throw IllegalStateException("星火大模型未返回候选回复")
        val content = firstChoice
            .getAsJsonObject("message")
            ?.get("content")
            ?.asString
            ?.trim()
            .orEmpty()
        check(content.isNotBlank()) { "星火大模型回复为空" }
        content
    }

    fun parseErrorMessage(json: String): String? {
        if (json.isBlank()) return null
        return runCatching {
            val root = JsonParser.parseString(json).asJsonObject
            val error = root.getAsJsonObject("error") ?: return null
            error.get("message")?.asString?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
