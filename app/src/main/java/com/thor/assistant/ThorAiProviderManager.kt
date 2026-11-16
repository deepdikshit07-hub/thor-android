package com.thor.assistant

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException

/**
 * Per-installation AI provider settings. API credentials are encrypted with an
 * Android Keystore AES-GCM key and never placed in source code or cloud sync.
 *
 * OpenAI-compatible adapters cover Groq, OpenRouter, Cloudflare Workers AI,
 * and compatible custom endpoints. Gemini continues to use Firebase AI Logic.
 */
class ThorAiProviderManager(private val context: Context) {
    enum class Provider(val title: String, val defaultModel: String, val defaultEndpoint: String) {
        AUTO("Automatic (recommended)", "", ""),
        GEMINI("Google Gemini Flash (Firebase)", "gemini-3.8-flash", ""),
        GEMINI_API("Google Gemini Flash (API key)", "gemini-3.8-flash", "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"),
        GROQ("Groq", "openai/gpt-oss-20b", "https://api.groq.com/openai/v1/chat/completions"),
        CLAUDE("Claude (Anthropic API)", "claude-haiku-5-5", "https://api.anthropic.com/v1/messages"),
        OPENAI("OpenAI / ChatGPT API", "gpt-4o-mini", "https://api.openai.com/v1/chat/completions"),
        OPENROUTER("OpenRouter", "meta-llama/llama-3.3-70b-instruct:free", "https://openrouter.ai/api/v1/chat/completions"),
        CLOUDFLARE("Cloudflare Workers AI", "@cf/meta/llama-3.1-8b-instruct", ""),
        CUSTOM("Custom OpenAI-compatible API", "", ""),
        LOCAL("On-device AI only", "", "")
    }

    data class Settings(
        val provider: Provider,
        val model: String,
        val endpoint: String,
        val apiKey: String
    )

    private val prefs = context.getSharedPreferences("thor_ai_provider", Context.MODE_PRIVATE)
    private val alias = "thor_ai_provider_aes_key"

    init {
        // One-time migration: enable the automatic mode requested for this THOR upgrade.
        // Provider-specific encrypted keys remain untouched.
        if (!prefs.getBoolean("auto_provider_migration_v1", false)) {
            prefs.edit()
                .putString("provider", Provider.AUTO.name)
                .putBoolean("auto_provider_migration_v1", true)
                .apply()
        }
    }

    fun allowMeteredAutoProviders(): Boolean =
        prefs.getBoolean("allow_metered_auto_providers", false)

    fun setAllowMeteredAutoProviders(enabled: Boolean) {
        prefs.edit().putBoolean("allow_metered_auto_providers", enabled).apply()
    }

    fun settings(forProvider: Provider? = null): Settings {
        val provider = forProvider ?: runCatching {
            Provider.valueOf(prefs.getString("provider", Provider.AUTO.name) ?: Provider.AUTO.name)
        }.getOrDefault(Provider.AUTO)
        val suffix = provider.name
        val savedModel = prefs.getString("model_$suffix", null)
        // Gemini now limits 2.5 model access for newly created projects.
        // Migrate the old THOR default automatically so existing installs do not
        // keep calling a model that may be unavailable to a new API project.
        val model = if (provider == Provider.GEMINI_API &&
            (savedModel.isNullOrBlank() || savedModel == "gemini-2.5-flash")
        ) {
            provider.defaultModel.also {
                prefs.edit().putString("model_$suffix", it).apply()
            }
        } else {
            savedModel ?: provider.defaultModel
        }
        return Settings(
            provider,
            model,
            prefs.getString("endpoint_$suffix", provider.defaultEndpoint) ?: provider.defaultEndpoint,
            decrypt(prefs.getString("api_key_$suffix", null))
        )
    }

    fun save(provider: Provider, model: String, endpoint: String, apiKey: String) {
        val encrypted = if (apiKey.isBlank()) null else encrypt(apiKey.trim())
        val suffix = provider.name
        prefs.edit()
            .putString("provider", provider.name)
            .putString("model_$suffix", model.trim().ifBlank { provider.defaultModel })
            .putString("endpoint_$suffix", endpoint.trim().ifBlank { provider.defaultEndpoint })
            .apply {
                if (encrypted != null) putString("api_key_$suffix", encrypted)
            }
            .apply()
    }

    fun clearKey(provider: Provider) {
        prefs.edit().remove("api_key_${provider.name}").apply()
    }

    data class AutomaticAnswer(val provider: Provider, val text: String)

    /**
     * Tries eligible no-cost-tier routes first. Metered Claude/OpenAI calls are
     * excluded unless the user explicitly enables them in THOR settings.
     */
    suspend fun askAutomatically(
        prompt: String,
        geminiFirebase: suspend () -> String
    ): AutomaticAnswer {
        val candidates = buildList {
            add(Provider.GEMINI)
            add(Provider.GROQ)
            add(Provider.GEMINI_API)
            if (allowMeteredAutoProviders()) {
                add(Provider.CLAUDE)
                add(Provider.OPENAI)
            }
        }
        val failures = mutableListOf<String>()
        for (provider in candidates) {
            if (provider != Provider.GEMINI) {
                val config = settings(provider)
                if (config.apiKey.isBlank()) continue
            }
            try {
                val answer = if (provider == Provider.GEMINI) {
                    geminiFirebase()
                } else {
                    ask(provider, prompt)
                }
                if (answer.isNotBlank()) return AutomaticAnswer(provider, answer)
                failures += "${provider.title}: empty response"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("THOR_AI_PROVIDER", "Automatic route ${provider.name} failed", error)
                failures += "${provider.title}: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        val detail = failures.takeLast(3).joinToString("; ")
        throw IllegalStateException(
            if (detail.isBlank()) {
                "No online provider is configured. Add a Groq or Gemini API key, or check the Firebase Gemini connection."
            } else {
                "All eligible online providers are unavailable. $detail"
            }
        )
    }

    suspend fun stream(provider: Provider, prompt: String, onChunk: (String) -> Unit, maxTokens: Int = 512): String = withContext(Dispatchers.IO) {
        val config = settings(provider)
        require(provider !in listOf(Provider.AUTO, Provider.LOCAL, Provider.GEMINI)) { "This provider uses a different streaming transport." }
        require(config.apiKey.isNotBlank()) { "Add an API key for " + provider.title + " in AI Provider settings." }
        val endpoint = config.endpoint.trim().ifBlank { provider.defaultEndpoint }
        require(endpoint.startsWith("https://", ignoreCase = true)) { "Provider endpoint must use HTTPS." }
        val body = if (provider == Provider.CLAUDE) {
            JSONObject().put("model", config.model).put("max_tokens", maxTokens.coerceIn(64, 1024)).put("stream", true)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt.take(12000))))
        } else {
            JSONObject().put("model", config.model).put("stream", true).put("max_tokens", maxTokens.coerceIn(64, 1024))
                .put("temperature", 0.7).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt.take(12000))))
        }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10000; readTimeout = 18000; doOutput = true
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "text/event-stream")
            if (provider == Provider.CLAUDE) { setRequestProperty("x-api-key", config.apiKey); setRequestProperty("anthropic-version", "2023-06-01") }
            else setRequestProperty("Authorization", "Bearer " + config.apiKey)
            if (provider == Provider.OPENROUTER) { setRequestProperty("HTTP-Referer", "https://github.com/deepdikshit07-hub/thor-android"); setRequestProperty("X-Title", "THOR Android Assistant") }
        }
        val full = StringBuilder()
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val raw = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val detail = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") ?: JSONObject(raw).optString("message") }.getOrNull().orEmpty().take(220).ifBlank { "HTTP " + status }
                if (status == 429) prefs.edit().putLong("cooldown_until_" + provider.name, System.currentTimeMillis() + 3600000L).apply()
                throw IllegalStateException(provider.title + " returned HTTP " + status + ": " + detail)
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (!line.startsWith("data:")) return@forEach
                    val data = line.removePrefix("data:").trim()
                    if (data.isBlank() || data == "[DONE]") return@forEach
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: return@forEach
                    val piece = if (provider == Provider.CLAUDE) {
                        if (json.optString("type") == "content_block_delta") json.optJSONObject("delta")?.optString("text").orEmpty() else ""
                    } else json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()
                    if (piece.isNotEmpty()) { full.append(piece); onChunk(piece) }
                    if (json.optString("type") == "error") throw IllegalStateException("Provider streaming error.")
                }
            }
            full.toString().takeIf { it.isNotBlank() } ?: error(provider.title + " returned no streamed text.")
        } finally { connection.disconnect() }
    }

    data class StreamAnswer(val provider: Provider, val text: String)

    suspend fun streamAutomatically(prompt: String, geminiFirebase: suspend ((String) -> Unit) -> String, onChunk: (String) -> Unit): StreamAnswer {
        val candidates = buildList {
            add(Provider.GEMINI)
            add(Provider.GROQ)
            add(Provider.GEMINI_API)
            if (allowMeteredAutoProviders()) {
                add(Provider.CLAUDE)
                add(Provider.OPENAI)
            }
        }
        val failures = mutableListOf<String>()
        for (provider in candidates) {
            if (provider != Provider.GEMINI && settings(provider).apiKey.isBlank()) continue
            val received = StringBuilder()
            try {
                val answer = when (provider) {
                    Provider.GEMINI -> geminiFirebase { part -> received.append(part); onChunk(part) }
                    Provider.GEMINI_API -> ask(provider, prompt).also { received.append(it); onChunk(it) }
                    else -> stream(provider, prompt, { part -> received.append(part); onChunk(part) })
                }
                if (answer.isNotBlank()) return StreamAnswer(provider, answer)
                failures += provider.title + ": empty response"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                Log.w("THOR_AI_PROVIDER", "Streaming route failed: " + provider.name, error)
                if (received.isNotEmpty()) throw IllegalStateException(provider.title + " stopped after a partial response. Retry the question.", error)
                failures += provider.title + ": " + (error.message ?: error.javaClass.simpleName)
            }
        }
        throw IllegalStateException(if (failures.isEmpty()) "No online AI provider is configured. Add a Gemini or Groq API key." else "All configured online providers failed. " + failures.takeLast(4).joinToString("; "))
    }

    suspend fun ask(prompt: String, maxTokens: Int = 192): String =
        ask(settings(), prompt, maxTokens)

    suspend fun ask(provider: Provider, prompt: String, maxTokens: Int = 192): String =
        ask(settings(provider), prompt, maxTokens)

    private suspend fun ask(config: Settings, prompt: String, maxTokens: Int): String = withContext(Dispatchers.IO) {
        val blockedUntil = prefs.getLong("cooldown_until_${config.provider.name}", 0L)
        if (System.currentTimeMillis() < blockedUntil) {
            val minutes = ((blockedUntil - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1L)
            error("${config.provider.title} quota cooldown is active for about $minutes more minutes.")
        }
        require(config.provider != Provider.AUTO) { "Automatic mode must use askAutomatically()." }
        require(config.provider != Provider.LOCAL) { "Local AI is selected." }
        require(config.provider != Provider.GEMINI) { "Gemini requests must use Firebase AI Logic." }
        require(config.apiKey.isNotBlank()) { "Add an API key for ${config.provider.title} in AI Provider settings." }
        require(config.model.isNotBlank()) { "Enter a model name." }
        if (config.provider == Provider.GEMINI_API) {
            return@withContext askGeminiApi(config, prompt, maxTokens)
        }
        if (config.provider == Provider.CLAUDE) {
            return@withContext askClaude(config, prompt, maxTokens)
        }
        val endpoint = config.endpoint.trim()
        require(endpoint.startsWith("https://", ignoreCase = true)) { "Provider endpoint must use HTTPS." }
        require(config.model.isNotBlank()) { "Enter a model name." }

        val body = JSONObject()
            .put("model", config.model)
            .put("messages", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("content", prompt.take(4000))))
            .put("max_tokens", maxTokens.coerceIn(64, 512))
            .put("temperature", 0.7)

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (config.provider == Provider.OPENROUTER) {
                setRequestProperty("HTTP-Referer", "https://github.com/deepdikshit07-hub/thor-android")
                setRequestProperty("X-Title", "THOR Android Assistant")
            }
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching {
                    JSONObject(response).optJSONObject("error")?.optString("message")
                        ?: JSONObject(response).optString("message")
                }.getOrNull().orEmpty()
                val safeDetail = detail.take(240).ifBlank { "HTTP $status" }
                if (status == 429) {
                    prefs.edit()
                        .putLong("cooldown_until_${config.provider.name}", System.currentTimeMillis() + 60L * 60L * 1000L)
                        .apply()
                }
                throw IllegalStateException(when (status) {
                    401, 403 -> "Provider rejected the API key or permission ($safeDetail)."
                    429 -> "Provider rate limit or free quota reached. A one-hour cooldown is now active. ($safeDetail)"
                    else -> "Provider request failed: $safeDetail"
                })
            }
            val json = JSONObject(response)
            val text = json.optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                ?.takeIf { it.isNotBlank() }
            text ?: error("Provider returned no message.")
        } catch (error: Exception) {
            Log.e("THOR_AI_PROVIDER", "AI provider request failed", error)
            throw error
        } finally {
            connection.disconnect()
        }
    }


    private fun askClaude(config: Settings, prompt: String, maxTokens: Int): String {
        val endpoint = config.endpoint.trim().ifBlank { Provider.CLAUDE.defaultEndpoint }
        require(endpoint.startsWith("https://", ignoreCase = true)) { "Anthropic endpoint must use HTTPS." }
        val body = JSONObject()
            .put("model", config.model)
            .put("max_tokens", maxTokens.coerceIn(64, 512))
            .put("messages", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("content", prompt.take(4000))))
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("x-api-key", config.apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching {
                    JSONObject(raw).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty().take(220).ifBlank { "HTTP $status" }
                if (status == 429) {
                    prefs.edit()
                        .putLong("cooldown_until_${config.provider.name}", System.currentTimeMillis() + 60L * 60L * 1000L)
                        .apply()
                }
                throw IllegalStateException(when (status) {
                    401, 403 -> "Anthropic rejected the API key or permissions ($detail)."
                    429 -> "Anthropic rate limit or credit limit reached. ($detail)"
                    else -> "Anthropic request failed: $detail"
                })
            }
            val blocks = JSONObject(raw).optJSONArray("content")
            val answer = buildString {
                if (blocks != null) for (i in 0 until blocks.length()) {
                    val block = blocks.optJSONObject(i)
                    if (block?.optString("type") == "text") {
                        val value = block.optString("text").trim()
                        if (value.isNotBlank()) {
                            if (isNotEmpty()) append('\n')
                            append(value)
                        }
                    }
                }
            }
            return answer.takeIf { it.isNotBlank() } ?: error("Claude returned no text.")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Gemini 3.8 Flash can occasionally be overloaded on the public API.
     * Give generation a realistic response window, then retry transient
     * overload/timeouts once on the stable 3.6 Flash fallback.
     */
    private suspend fun askGeminiApi(config: Settings, prompt: String, maxTokens: Int): String {
        val primaryError: Exception
        try {
            return requestGeminiApi(config, prompt, maxTokens, config.model, 28_000)
        } catch (error: Exception) {
            primaryError = error
        }
        if (!isTransientGeminiFailure(primaryError)) throw primaryError

        // Retry briefly, then use a lighter stable model if the primary is still unavailable.
        delay(700L)
        try {
            return requestGeminiApi(config, prompt, maxTokens, config.model, 18_000)
        } catch (retryError: Exception) {
            if (!isTransientGeminiFailure(retryError)) throw retryError
            Log.w("THOR_AI_PROVIDER", "Primary Gemini model is still unavailable; trying the lighter fallback.", retryError)
            val fallbackModel = "gemini-3.5-flash-lite"
            delay(500L)
            return try {
                requestGeminiApi(config, prompt, maxTokens, fallbackModel, 18_000)
            } catch (fallbackError: Exception) {
                Log.e("THOR_AI_PROVIDER", "Gemini primary and fallback requests both failed", fallbackError)
                throw IllegalStateException(
                    "Gemini could not complete the request. Primary: " + (primaryError.message ?: primaryError.javaClass.simpleName) +
                        ". Retry: " + (retryError.message ?: retryError.javaClass.simpleName) +
                        ". Fallback: " + (fallbackError.message ?: fallbackError.javaClass.simpleName) +
                        ". Check internet access, API key permissions, and Google AI Studio project status.",
                    fallbackError
                )
            }
        }
    }
    private fun isTransientGeminiFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is SocketTimeoutException) return true
            if (current is GeminiTransientException) return true
            current = current.cause
        }
        return false
    }

    private class GeminiTransientException(message: String) : java.io.IOException(message)

    private fun requestGeminiApi(
        config: Settings,
        prompt: String,
        maxTokens: Int,
        requestModel: String,
        readTimeoutMs: Int
    ): String {
        val endpoint = config.endpoint.trim()
            .replace("{model}", requestModel)
            .replace(config.model, requestModel)
        require(endpoint.startsWith("https://", ignoreCase = true)) { "Gemini endpoint must use HTTPS." }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt.take(4000))))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens.coerceIn(64, 512)))
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("x-goog-api-key", config.apiKey)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }
                    .getOrNull().orEmpty().take(220).ifBlank { "HTTP $status" }
                if (status in listOf(408, 500, 502, 503, 504)) {
                    throw GeminiTransientException("Gemini temporarily returned HTTP $status ($detail).")
                }
                if (status == 429) {
                    prefs.edit()
                        .putLong("cooldown_until_${config.provider.name}", System.currentTimeMillis() + 60L * 60L * 1000L)
                        .apply()
                }
                throw IllegalStateException(when (status) {
                    400 -> "Gemini rejected the request ($detail). Check the model and request settings."
                    401, 403 -> "Gemini rejected the API key or permissions ($detail). Check that the key is active and allowed for the Generative Language API. Older unrestricted standard keys may need migration to a Google AI Studio authorization key."
                    404 -> "Gemini model '$requestModel' is unavailable to this API project. ($detail)"
                    429 -> "Gemini API quota/rate limit reached. A one-hour cooldown is now active. ($detail)"
                    else -> "Gemini API request failed: $detail"
                })
            }
            val parts = JSONObject(raw).optJSONArray("candidates")
                ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val answer = buildString {
                if (parts != null) for (i in 0 until parts.length()) {
                    val value = parts.optJSONObject(i)?.optString("text").orEmpty()
                    if (value.isNotBlank()) { if (isNotEmpty()) append('\n'); append(value) }
                }
            }
            return answer.takeIf { it.isNotBlank() } ?: error("Gemini returned no text. Check the selected model and key permissions.")
        } finally {
            connection.disconnect()
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                alias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return try {
            val combined = Base64.decode(value, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, 12)
            val payload = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(payload), Charsets.UTF_8)
        } catch (error: Exception) {
            Log.w("THOR_AI_PROVIDER", "Stored API key could not be decrypted; clear and re-enter it.", error)
            ""
        }
    }
}
