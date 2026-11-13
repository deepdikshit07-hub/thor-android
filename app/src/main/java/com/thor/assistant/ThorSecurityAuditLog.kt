package com.thor.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local-only event history; never stores passwords, tokens, biometric data or message contents. */
class ThorSecurityAuditLog(context: Context) {
    private val prefs = context.getSharedPreferences("thor_security_activity", Context.MODE_PRIVATE)

    @Synchronized
    fun record(event: String) {
        val items = read()
        items.put(JSONObject()
            .put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            .put("event", event.take(120)))
        while (items.length() > 100) items.remove(0)
        prefs.edit().putString("events", items.toString()).apply()
    }

    @Synchronized
    fun events(): List<Pair<String, String>> {
        val items = read()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val time = item.optString("time")
            val event = item.optString("event")
            if (time.isBlank() || event.isBlank()) null else time to event
        }.reversed()
    }

    @Synchronized
    fun clear() { prefs.edit().remove("events").apply() }

    private fun read(): JSONArray = try {
        JSONArray(prefs.getString("events", "[]") ?: "[]")
    } catch (_: Exception) { JSONArray() }
}
