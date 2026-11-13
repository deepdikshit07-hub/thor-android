package com.thor.assistant

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.ai
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * Gemini access through Firebase AI Logic.
 * Uses Firebase's managed proxy; no Gemini API key is stored in the app.
 */
object GeminiAssistant {
    private const val TAG = "THOR_GEMINI"
    private const val REQUEST_TIMEOUT_MS = 60_000L

    private val model by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel("gemini-3.8-flash")
    }

    suspend fun ask(prompt: String): String = withTimeout(REQUEST_TIMEOUT_MS) {
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
