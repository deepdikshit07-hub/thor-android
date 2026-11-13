package com.thor.assistant

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * HTTPS client for the THOR Cloudflare approval Worker.
 * No API key or service-account credential is stored in this app.
 */
class ThorMasterApprovalClient(context: Context) {
    private val prefs = context.getSharedPreferences("thor_master_approval", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("worker_url", "").orEmpty().trimEnd('/')
        set(value) { prefs.edit().putString("worker_url", value.trim().trimEnd('/')).apply() }

    val deviceId: String
        get() {
            val saved = prefs.getString("device_id", null)
            if (!saved.isNullOrBlank()) return saved
            val created = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", created).apply()
            return created
        }

    fun saveSessionId(value: String) { prefs.edit().putString("session_id", value).apply() }
    fun sessionId(): String = prefs.getString("session_id", "").orEmpty()
    fun clearSessionId() { prefs.edit().remove("session_id").apply() }

    private fun requireBaseUrl(): String {
        val value = baseUrl
        require(value.startsWith("https://") && value.length < 300) { "Set the HTTPS Cloudflare Worker URL first." }
        return value
    }

    private fun idToken(): String {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in to Firebase on this phone first.")
        return Tasks.await(user.getIdToken(true)).token ?: error("Firebase did not provide an ID token.")
    }

    private suspend fun call(path: String, method: String = "GET", body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val connection = URL(requireBaseUrl() + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.setRequestProperty("Authorization", "Bearer ${idToken()}")
                connection.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val result = if (text.isBlank()) JSONObject() else JSONObject(text)
                if (code !in 200..299) error(result.optString("error", "Master approval service returned HTTP $code."))
                result
            } finally {
                connection.disconnect()
            }
        }

    suspend fun registerApprover(publicKeyBase64: String, fcmToken: String) =
        call("/v1/devices/register", "POST", JSONObject().put("deviceId", deviceId)
            .put("role", "approver").put("publicKeyBase64", publicKeyBase64).put("fcmToken", fcmToken))

    suspend fun registerRequester() =
        call("/v1/devices/register", "POST", JSONObject().put("deviceId", deviceId).put("role", "requester"))

    suspend fun requestApproval(): JSONObject =
        call("/v1/requests", "POST", JSONObject().put("deviceId", deviceId))

    suspend fun pendingRequests(): JSONArray = call("/v1/requests/pending?deviceId=$deviceId").optJSONArray("requests") ?: JSONArray()

    suspend fun requestDetails(requestId: String): JSONObject =
        call("/v1/requests/$requestId?deviceId=$deviceId")

    suspend fun approve(requestId: String, signatureBase64: String): JSONObject =
        call("/v1/requests/$requestId/approve", "POST", JSONObject()
            .put("deviceId", deviceId).put("signatureBase64", signatureBase64))

    suspend fun deny(requestId: String): JSONObject =
        call("/v1/requests/$requestId/deny", "POST", JSONObject().put("deviceId", deviceId))

    suspend fun requestResult(requestId: String): JSONObject = call("/v1/requests/$requestId/result")

    suspend fun checkSession(): Boolean? {
        val id = sessionId()
        if (id.isBlank()) return false
        return try { call("/v1/sessions/$id/check").optBoolean("active", false) }
        catch (error: Exception) {
            if (error.message.orEmpty().contains("Forbidden", ignoreCase = true) ||
                error.message.orEmpty().contains("not found", ignoreCase = true)) false
            else null // Network/service failure is unknown, not proof of revocation.
        }
    }

    suspend fun revokeSession(session: String): JSONObject =
        call("/v1/sessions/$session/revoke", "POST", JSONObject().put("deviceId", deviceId))

    suspend fun endSession(session: String): JSONObject =
        call("/v1/sessions/$session/end", "POST", JSONObject())

    suspend fun activeSessions(): JSONArray =
        call("/v1/sessions/active?deviceId=$deviceId").optJSONArray("sessions") ?: JSONArray()
}
