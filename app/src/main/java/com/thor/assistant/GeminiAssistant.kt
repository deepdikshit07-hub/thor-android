package com.thor.assistant

import com.google.firebase.Firebase
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.ai
import kotlinx.coroutines.withTimeout

/**
 * Gemini access through Firebase AI Logic.
 * Uses Firebase's managed proxy; no Gemini API key is stored in the app.
 */
object GeminiAssistant {
    private val model by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel("gemini-3.8-flash")
    }

    suspend fun ask(prompt: String): String {
        // Prevent the UI from waiting forever if the network or Firebase request stalls.
        val response = withTimeout(30_000L) {
            model.generateContent(prompt)
        }
        return response.text?.takeIf { it.isNotBlank() }
            ?: "Gemini returned an empty response. Please try again."
    }
}
