package com.thor.assistant

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Per-installation AI provider settings. API credentials are encrypted with an
 * Android Keystore AES-GCM key and never placed in source code or cloud sync.
 *
 * OpenAI-compatible adapters cover Groq, OpenRouter, Cloudflare Workers AI,
 * and compatible custom endpoints. Gemini continues to use Firebase AI Logic.
 */
class ThorAiProviderManager(private val context: Context) {
    enum class Provider(val title: String, val defaultModel: String, val defaultEndpoint: String) {
        GEMINI("Google Gemini (Firebase)", "gemini-3.8-flash", ""),
        GROQ("Groq", "llama-3.3-70b-versatile", "https://api.groq.com/openai/v1/chat/completions"),
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

    fun settings(forProvider: Provider? = null): Settings {
        val provider = forProvider ?: runCatching {
            Provider.valueOf(prefs.getString("provider", Provider.GEMINI.name) ?: Provider.GEMINI.name)
        }.getOrDefault(Provider.GEMINI)
        val suffix = provider.name
        return Settings(
            provider,
            prefs.getString("model_$suffix", provider.defaultModel) ?: provider.defaultModel,
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

    suspend fun ask(prompt: String, maxTokens: Int = 256): String = withContext(Dispatchers.IO) {
        val config = settings()
        require(config.provider != Provider.LOCAL) { "Local AI is selected." }
        require(config.provider != Provider.GEMINI) { "Gemini requests must use Firebase AI Logic." }
        require(config.apiKey.isNotBlank()) { "Add an API key for ${config.provider.title} in AI Provider settings." }
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
            val response = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (status !in 200..299) {
                val detail = runCatching {
                    JSONObject(response).optJSONObject("error")?.optString("message")
                        ?: JSONObject(response).optString("message")
                }.getOrNull().orEmpty()
                val safeDetail = detail.take(240).ifBlank { "HTTP $status" }
                throw IllegalStateException(when (status) {
                    401, 403 -> "Provider rejected the API key or permission ($safeDetail)."
                    429 -> "Provider rate limit or free quota reached. ($safeDetail)"
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
