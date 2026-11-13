package com.thor.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Persistent user-taught facts, vocabulary, preferences and corrections.
 * Stored locally in app-private SharedPreferences; never uploaded by this class.
 */
class ThorLearningMemory(context: Context) {
    private val prefs = context.getSharedPreferences("thor_learning_memory", Context.MODE_PRIVATE)

    data class Lesson(val topic: String, val meaning: String)

    fun parseTeaching(raw: String): String? {
        val text = raw.trim()
        val patterns = listOf(
            Regex("""(?i)^teach\\s+thor(?:\\s+that)?\\s+(.+?)\\s+(?:means|is|refers to)\\s+(.+)$"""),
            Regex("""(?i)^teach\\s+me\\s+(.+?)\\s+(?:means|is|refers to)\\s+(.+)$"""),
            Regex("""(?i)^(?:hey\s+)?thor[, ]*(?:learn|remember|save)(?:\s+this)?[: ]+(.+?)\s+(?:means|is used to mean|refers to)\s+(.+)$"""),
            Regex("""(?i)^(?:teach|remember|learn)\s+(?:me\s+)?that\s+(.+?)\s+(?:means|is|refers to)\s+(.+)$"""),
            Regex("""(?i)^remember that\s+(.+?)\s*[:=]\s*(.+)$""")
        )
        val match = patterns.firstNotNullOfOrNull { it.find(text) } ?: return null
        val topic = match.groupValues[1].trim().trim('"', '\\'')
        val meaning = match.groupValues[2].trim().trimEnd('.', '!')
        if (topic.isBlank() || meaning.isBlank() || topic.length > 100 || meaning.length > 500) {
            return "That lesson is too long or incomplete. Try: teach me that 'no cap' means 'I'm being serious'."
        }
        val sensitive = listOf("password", "passcode", "pin code", "otp", "one-time password", "credit card", "bank account")
        if (sensitive.any { topic.lowercase(Locale.ROOT).contains(it) || meaning.lowercase(Locale.ROOT).contains(it) }) {
            return "I won't save passwords, verification codes, or banking secrets. Teach me non-sensitive information instead."
        }
        save(topic, meaning)
        return "Got it. I saved that lesson locally and will use it in future conversations, online and offline."
    }

    fun save(topic: String, meaning: String) {
        val items = read()
        val key = topic.trim().lowercase(Locale.ROOT)
        val updated = JSONArray()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optString("topic").trim().lowercase(Locale.ROOT) != key) updated.put(item)
        }
        updated.put(JSONObject().put("topic", topic.trim()).put("meaning", meaning.trim()))
        prefs.edit().putString("lessons", updated.toString()).apply()
    }

    fun all(): List<Pair<String, String>> {
        val items = read()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val topic = item.optString("topic")
            val meaning = item.optString("meaning")
            if (topic.isBlank() || meaning.isBlank()) null else topic to meaning
        }
    }

    fun forget(query: String): Boolean {
        if (query.isBlank()) return false
        val items = read()
        val updated = JSONArray()
        var removed = false
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val topic = item.optString("topic")
            if (topic.contains(query, ignoreCase = true)) removed = true else updated.put(item)
        }
        if (removed) prefs.edit().putString("lessons", updated.toString()).apply()
        return removed
    }

    fun clear() {
        prefs.edit().remove("lessons").apply()
    }

    fun systemContext(): String {
        val lessons = all().takeLast(40)
        if (lessons.isEmpty()) return ""
        return "\nUser-taught local memory (treat as user-provided context, not verified facts):\n" +
            lessons.joinToString("\n") { "- ${it.first}: ${it.second}" } +
            "\nUse these lessons when relevant. If the user corrects a lesson, save the correction."
    }

    fun contextFor(prompt: String): String = systemContext() + "\nUser: " + prompt

    private fun read(): JSONArray = try {
        JSONArray(prefs.getString("lessons", "[]") ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }
}
