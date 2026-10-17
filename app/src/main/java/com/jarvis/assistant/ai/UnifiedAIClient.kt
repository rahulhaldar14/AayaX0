package com.jarvis.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okio.BufferedSource

class UnifiedAIClient(private val repository: AIProviderRepository) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun discoverModels(config: AIProviderConfig): List<AIModel> = withContext(Dispatchers.IO) {
        val key = repository.apiKey(config.providerType)
        require(key.isNotBlank()) { "Enter and save an API key first." }
        val url = if (config.providerType == AIProviderType.GEMINI) {
            "${config.baseUrl.trimEnd('/')}/models"
        } else "${openAiBase(config)}/models"
        val request = requestBuilder(url, config.providerType, key).get().build()
        execute(request, key) { body ->
            val root = JSONObject(body)
            val rows = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            (0 until rows.length()).mapNotNull { i ->
                val row = rows.optJSONObject(i) ?: return@mapNotNull null
                val id = row.optString("id").ifBlank { row.optString("name").removePrefix("models/") }
                val methods = row.optJSONArray("supportedGenerationMethods")
                val supportsText = methods == null || (0 until methods.length()).any { index ->
                    methods.optString(index) in setOf("generateContent", "streamGenerateContent")
                }
                if (id.isBlank() || !supportsText) null else AIModel(
                    id,
                    row.optString("displayName").ifBlank { row.optString("name", id) },
                    methods?.let { list -> (0 until list.length()).any { list.optString(it) == "streamGenerateContent" } } ?: true
                )
            }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
        }
    }

    suspend fun test(config: AIProviderConfig): String = withContext(Dispatchers.IO) {
        val key = repository.apiKey(config.providerType)
        require(key.isNotBlank()) { "Enter and save an API key first." }
        require(config.modelId.isNotBlank()) { "Choose or enter a model ID first." }
        val started = System.currentTimeMillis()
        requestCompletion(config, key, "Reply with the single word OK.")
        "Model responded in ${System.currentTimeMillis() - started} ms."
    }

    suspend fun chat(text: String, onPartialResponse: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val type = repository.activeType()
        require(type in AIProviderType.textProviders) { "Select a text AI provider in AI Providers settings." }
        val config = repository.get(type) ?: error("Configure this provider in AI Providers settings first.")
        require(config.validated) { "Test this provider and model in AI Providers settings first." }
        val key = repository.apiKey(type)
        require(key.isNotBlank()) { "Saved API key is missing. Re-enter it in AI Providers settings." }
        require(config.modelId.isNotBlank()) { "Select a model in AI Providers settings." }
        requestCompletion(config, key, text, stream = config.streamingEnabled, onPartialResponse = onPartialResponse)
    }

    private suspend fun requestCompletion(
        config: AIProviderConfig,
        key: String,
        prompt: String,
        stream: Boolean = false,
        onPartialResponse: (String) -> Unit = {}
    ): String {
        val type = config.providerType
        val (url, payload) = if (type == AIProviderType.GEMINI) {
            val model = config.modelId.removePrefix("models/")
            val method = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
            "${config.baseUrl.trimEnd('/')}/models/${URLEncoder.encode(model, "UTF-8")}:$method" to
                JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                    .put("generationConfig", JSONObject().put("maxOutputTokens", 256)).toString()
        } else {
            val messages = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
            val requestBody = JSONObject()
                .put("model", config.modelId)
                .put("messages", messages)
                .put(if (type == AIProviderType.OPENAI) "max_completion_tokens" else "max_tokens", 256)
                .put("stream", stream)
                .toString()
            "${openAiBase(config)}/chat/completions" to requestBody
        }
        val request = requestBuilder(url, type, key)
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .post(payload.toRequestBody(JSON.toMediaType()))
            .build()
        if (stream) return executeStream(request, key, type, onPartialResponse)
        return execute(request, key) { body ->
            val root = JSONObject(body)
            val result = if (type == AIProviderType.GEMINI) {
                root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                    ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
            } else {
                root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            }
            result?.takeIf { it.isNotBlank() } ?: error("The provider returned no text response.")
        }
    }

    private fun openAiBase(config: AIProviderConfig): String = config.baseUrl.trimEnd('/').ifBlank {
        AIProviderRepository.defaultBase(config.providerType)
    }

    private fun requestBuilder(url: String, type: AIProviderType, key: String): Request.Builder {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (type == AIProviderType.GEMINI) builder.header("x-goog-api-key", key)
        else builder.header("Authorization", "Bearer $key")
        if (type == AIProviderType.OPENROUTER) {
            builder.header("HTTP-Referer", "https://ayha.ai").header("X-Title", "AYHA AI")
        }
        return builder
    }

    private suspend fun <T> execute(request: Request, secret: String, parse: (String) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    try {
                        val body = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
                            val safe = message?.takeIf { text -> text.isNotBlank() }
                                ?.replace(Regex("(?i)(key|token|authorization)[=: ]+[^ ,}]+"), "$1=[redacted]")
                                ?.replace(secret, "[redacted]")
                            error("Provider request failed (HTTP ${it.code})${safe?.let { text -> ": $text" } ?: ""}")
                        }
                        val parsed = parse(body)
                        if (continuation.isActive) continuation.resume(parsed)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }

    private suspend fun executeStream(
        request: Request,
        secret: String,
        type: AIProviderType,
        onPartialResponse: (String) -> Unit
    ): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { result ->
                    try {
                        val body = result.body ?: error("Provider returned an empty response.")
                        if (!result.isSuccessful) throw providerError(result.code, body.string(), secret)
                        val accumulated = StringBuilder()
                        val source: BufferedSource = body.source()
                        while (!source.exhausted() && continuation.isActive) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data.isEmpty() || data == "[DONE]") continue
                            val chunk = parseStreamChunk(data, type)
                            if (chunk.isNotEmpty()) {
                                accumulated.append(chunk)
                                onPartialResponse(accumulated.toString())
                            }
                        }
                        if (continuation.isActive) {
                            if (accumulated.isEmpty()) error("The provider returned no streamed text. Check whether the selected model supports streaming.")
                            continuation.resume(accumulated.toString())
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }

    private fun parseStreamChunk(data: String, type: AIProviderType): String = runCatching {
        val root = JSONObject(data)
        if (type == AIProviderType.GEMINI) {
            val parts = root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: return@runCatching ""
            buildString { for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty()) }
        } else {
            root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()
        }
    }.getOrDefault("")

    private fun providerError(code: Int, body: String, secret: String): IllegalStateException {
        val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
        val safe = message?.takeIf { it.isNotBlank() }
            ?.replace(Regex("(?i)(key|token|authorization)[=: ]+[^ ,}]+"), "$1=[redacted]")
            ?.replace(secret, "[redacted]")
        return IllegalStateException("Provider request failed (HTTP $code)${safe?.let { ": $it" } ?: ""}")
    }

    companion object { private const val JSON = "application/json; charset=utf-8" }
}
