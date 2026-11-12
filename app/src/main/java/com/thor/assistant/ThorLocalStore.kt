package com.thor.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight local-only notes and task storage.
 * Data stays in THOR's private app preferences on this device.
 */
class ThorLocalStore(context: Context) {
    private val preferences = context.getSharedPreferences("thor_local_data", Context.MODE_PRIVATE)

    fun addNote(text: String) {
        val items = readArray("notes")
        items.put(text)
        preferences.edit().putString("notes", items.toString()).apply()
    }

    fun notes(): List<String> {
        val items = readArray("notes")
        return (0 until items.length()).mapNotNull { index ->
            items.optString(index).takeIf { it.isNotBlank() }
        }
    }

    fun addTask(text: String) {
        val items = readArray("tasks")
        items.put(JSONObject().put("text", text).put("done", false))
        preferences.edit().putString("tasks", items.toString()).apply()
    }

    fun tasks(): List<Pair<String, Boolean>> {
        val items = readArray("tasks")
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            item.optString("text").takeIf { it.isNotBlank() }
                ?.let { it to item.optBoolean("done", false) }
        }
    }

    fun completeTask(index: Int): Boolean {
        val items = readArray("tasks")
        if (index !in 0 until items.length()) return false
        val item = items.optJSONObject(index) ?: return false
        item.put("done", true)
        preferences.edit().putString("tasks", items.toString()).apply()
        return true
    }

    private fun readArray(key: String): JSONArray {
        val saved = preferences.getString(key, null) ?: return JSONArray()
        return try {
            JSONArray(saved)
        } catch (_: Exception) {
            JSONArray()
        }
    }
}
