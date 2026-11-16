package com.thor.assistant

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.ai
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect

/**
 * Gemini access through Firebase AI Logic.
 * Uses Firebase's managed proxy; no Gemini API key is stored in the app.
 */
object GeminiAssistant {
    private const val TAG = "THOR_GEMINI"
    private const val REQUEST_TIMEOUT_MS = 30_000L

    private val model by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel(
                modelName = "gemini-3.8-flash",
                generationConfig = generationConfig { maxOutputTokens = 192 }
            )
    }

    suspend fun ask(prompt: String): String = withTimeout(REQUEST_TIMEOUT_MS) {
        try {
            val response = model.generateContent(prompt)
            response.text?.takeIf { it.isNotBlank() }
                ?: "Gemini returned an empty response. Please try again."
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Gemini request failed", error)
            throw IllegalStateException(
                error.message ?: "The Gemini service could not be reached. Check internet and Firebase App Check.",
                error
            )
        }
    }

    /**
     * Streams partial text as Gemini generates it so the chat can respond
     * immediately instead of waiting for the entire answer.
     */
    suspend fun askStreaming(prompt: String, onChunk: (String) -> Unit): String =
        withTimeout(REQUEST_TIMEOUT_MS) {
            try {
                val answer = StringBuilder()
                model.generateContentStream(prompt).collect { response ->
                    val part = response.text.orEmpty()
                    if (part.isNotEmpty()) {
                        answer.append(part)
                        onChunk(part)
                    }
                }
                answer.toString().trim().ifBlank {
                    "Gemini returned an empty response. Please try again."
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Gemini streaming request failed", error)
                throw IllegalStateException(
                    error.message ?: "The Gemini service could not be reached. Check internet and Firebase App Check.",
                    error
                )
            }
        }
}
