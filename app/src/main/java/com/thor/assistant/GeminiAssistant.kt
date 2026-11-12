package com.thor.assistant

import com.google.firebase.Firebase
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.ai

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
        val response = model.generateContent(prompt)
        return response.text?.takeIf { it.isNotBlank() }
            ?: "I couldn't generate a response. Please try again."
    }
}
