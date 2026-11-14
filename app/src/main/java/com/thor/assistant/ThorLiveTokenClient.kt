package com.thor.assistant

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Obtains a single-use, short-lived Gemini Live token from THOR's authenticated
 * Cloudflare Worker. The permanent Gemini API key never enters the APK.
 */
class ThorLiveTokenClient {
    suspend fun create(workerUrl: String, voiceName: String): String = withContext(Dispatchers.IO) {
        val base = workerUrl.trim().trimEnd('/')
        require(base.startsWith("https://") && base.endsWith(".workers.dev")) {
            "Configure the HTTPS THOR Cloudflare Worker URL in Master Mode setup first."
        }
        val user = FirebaseAuth.getInstance().currentUser
            ?: error("Sign in to your THOR Firebase account before starting Live Mode.")
        val idToken = Tasks.await(user.getIdToken(true)).token
            ?: error("Firebase did not provide a sign-in token.")
        val connection = URL("$base/v1/live-token").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 12_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $idToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            val body = JSONObject().put("voiceName", voiceName)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = if (payload.isBlank()) JSONObject() else JSONObject(payload)
            if (status !in 200..299) {
                error(json.optString("error", "Live token service returned HTTP $status."))
            }
            json.optString("token").takeIf { it.isNotBlank() }
                ?: error("Live token service returned no token.")
        } finally {
            connection.disconnect()
        }
    }
}
