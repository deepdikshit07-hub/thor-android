package com.thor.assistant

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.ai
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect

/**
 * Gemini access through Firebase AI Logic.
 * Uses Firebase's managed proxy; no Gemini API key is stored in the app.
 */
object GeminiAssistant {
    private const val TAG = "THOR_GEMINI"
    private const val REQUEST_TIMEOUT_MS = 60_000L

    suspend fun stream(prompt: String, modelName: String = "gemini-3.8-flash", onChunk: (String) -> Unit): String = withTimeout(REQUEST_TIMEOUT_MS) {
        val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(modelName)
        val full = StringBuilder()
        model.generateContentStream(prompt).collect { chunk ->
            val piece = chunk.text.orEmpty()
            if (piece.isNotEmpty()) { full.append(piece); onChunk(piece) }
        }
        full.toString().takeIf { it.isNotBlank() } ?: error("Gemini returned an empty streamed response.")
    }

    suspend fun ask(prompt: String, modelName: String = "gemini-3.8-flash"): String = withTimeout(REQUEST_TIMEOUT_MS) {
        val model = Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel(modelName)
        var lastError: Exception? = null

        // A short retry helps when App Check is refreshing a token or the mobile
        // connection briefly drops. Cancellation is always allowed to propagate.
        repeat(2) { attempt ->
            try {
                val response = model.generateContent(prompt)
                return@withTimeout response.text?.takeIf { it.isNotBlank() }
                    ?: "Gemini returned an empty response. Please try again."
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                Log.w(TAG, "Gemini attempt ${attempt + 1} failed", error)
                if (attempt == 0) delay(1_200L)
            }
        }

        val error = lastError
        Log.e(TAG, "Gemini request failed after retry", error)
        throw IllegalStateException(
            error?.message ?: "The Gemini service could not be reached. Check internet and Firebase App Check.",
            error
        )
    }
}
