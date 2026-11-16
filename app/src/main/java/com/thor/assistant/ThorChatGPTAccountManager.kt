package com.thor.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.withContext

/**
 * Native Sign in with ChatGPT for THOR's open-source Android client.
 *
 * This uses the documented OpenAI OAuth loopback flow and Responses API only.
 * It does not scrape ChatGPT, use website cookies, or ask for a password/API key.
 * OAuth credentials are encrypted at rest with Android Keystore.
 */
class ThorChatGPTAccountManager(private val context: Context) {
    data class AccountStatus(val connected: Boolean, val planUsageEnabled: Boolean, val email: String)

    data class Model(val slug: String, val displayName: String)

    private val prefs = context.getSharedPreferences("thor_chatgpt_account", Context.MODE_PRIVATE)
    private val keyAlias = "thor_chatgpt_oauth_aes_v1"
    private val resource = "https://api.openai.com/v1"
    private val discoveryUrl = "https://auth.openai.com/.well-known/openid-configuration"
    private val hostId: String
        get() = prefs.getString("host_id", null) ?: ("urn:uuid:" + UUID.randomUUID().toString()).also {
            prefs.edit().putString("host_id", it).apply()
        }

    fun status(): AccountStatus {
        val token = decrypt(prefs.getString("access_token", null))
        val scopes = prefs.getString("scope", "").orEmpty().split(' ').toSet()
        return AccountStatus(
            connected = !token.isNullOrBlank() && prefs.getString("client_id", null) != null,
            planUsageEnabled = scopes.contains("chatgpt.tokens.use.direct") && scopes.contains("resource.invoke"),
            email = prefs.getString("email", "").orEmpty()
        )
    }

    suspend fun disconnect(): Boolean = withContext(Dispatchers.IO) {
        val refresh = decrypt(prefs.getString("refresh_token", null))
        val clientId = prefs.getString("client_id", null)
        var revoked = false
        if (!refresh.isNullOrBlank() && !clientId.isNullOrBlank()) {
            revoked = runCatching {
                val discovery = getJson(discoveryUrl)
                val endpoint = discovery.optString("revocation_endpoint")
                endpoint.isNotBlank() && postFormStatus(endpoint, linkedMapOf(
                    "token" to refresh,
                    "token_type_hint" to "refresh_token",
                    "client_id" to clientId
                )) == 200
            }.getOrDefault(false)
        }
        // Keep the issued client ID and validated account label so the next
        // sign-in reuses the existing registration instead of creating another.
        prefs.edit()
            .remove("access_token").remove("refresh_token")
            .remove("id_token").remove("expires_at").remove("scope")
            .apply()
        revoked
    }

    suspend fun signIn(activity: Activity): AccountStatus = withContext(Dispatchers.IO) {
        val discovery = getJson(discoveryUrl)
        val authorizeEndpoint = discovery.getString("authorization_endpoint")
        val tokenEndpoint = discovery.getString("token_endpoint")
        val jwksUri = discovery.getString("jwks_uri")
        val state = randomUrlToken(32)
        val nonce = randomUrlToken(32)
        val verifier = randomUrlToken(48)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 180_000
            val redirectUri = "http://127.0.0.1:${server.localPort}/auth/callback"
            val existingClient = prefs.getString("client_id", null)
            val params = linkedMapOf(
                "client_id" to (existingClient ?: "dynamic_agent_client"),
                "response_type" to "code",
                "redirect_uri" to redirectUri,
                "scope" to "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
                "resource" to resource,
                "state" to state,
                "nonce" to nonce,
                "code_challenge_method" to "S256",
                "code_challenge" to challenge,
                "ext_agent_host_id" to hostId
            )
            if (existingClient == null) {
                params["agent_name_hint"] = "THOR"
            } else {
                decrypt(prefs.getString("id_token", null))?.let { params["id_token_hint"] = it }
                prefs.getString("email", null)?.takeIf { it.isNotBlank() }?.let { params["login_hint"] = it }
            }
            val authUrl = authorizeEndpoint + "?" + params.entries.joinToString("&") {
                enc(it.key) + "=" + enc(it.value)
            }

            withContext(Dispatchers.Main) {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
            }

            val callback = withTimeout(180_000L) {
                val socket = server.accept()
                socket.use { client ->
                    client.soTimeout = 10_000
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                    val requestLine = reader.readLine().orEmpty()
                    // Consume headers before writing the callback response.
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    val target = requestLine.split(" ").getOrNull(1).orEmpty()
                    val callbackUri = Uri.parse("http://127.0.0.1:${server.localPort}$target")
                    val queryState = callbackUri.getQueryParameter("state")
                    val code = callbackUri.getQueryParameter("code")
                    val returnedClientId = callbackUri.getQueryParameter("client_id")
                    val oauthError = callbackUri.getQueryParameter("error")
                    val html = if (oauthError == null && code != null && queryState == state) {
                        "<html><body><h2>THOR connected</h2><p>You can return to THOR.</p></body></html>"
                    } else {
                        "<html><body><h2>THOR sign-in not completed</h2><p>Return to THOR and try again.</p></body></html>"
                    }
                    val bytes = html.toByteArray(StandardCharsets.UTF_8)
                    client.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\nContent-Length: ${bytes.size}\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
                        write(bytes)
                        flush()
                    }
                    if (!target.startsWith("/auth/callback?") || queryState != state) {
                        error("ChatGPT sign-in callback could not be verified. Please try again.")
                    }
                    if (oauthError != null) error("ChatGPT sign-in was cancelled or denied: $oauthError")
                    if (code.isNullOrBlank()) error("ChatGPT did not return an authorization code.")
                    if (existingClient != null && !returnedClientId.isNullOrBlank() && returnedClientId != existingClient) {
                        error("OpenAI returned a different account registration. Reconnect the original account.")
                    }
                    val clientId = returnedClientId?.takeIf { it.isNotBlank() } ?: existingClient
                        ?: error("OpenAI did not return the registered client ID.")
                    Triple(code, clientId, redirectUri)
                }
            }

            val form = linkedMapOf(
                "grant_type" to "authorization_code",
                "client_id" to callback.second,
                "code" to callback.first,
                "code_verifier" to verifier,
                "redirect_uri" to callback.third,
                "resource" to resource
            )
            val token = postForm(tokenEndpoint, form)
            val idToken = token.optString("id_token")
            if (idToken.isBlank()) error("ChatGPT did not return an identity token.")
            val identity = verifyIdToken(idToken, callback.second, nonce, jwksUri)
            val previousSubject = prefs.getString("subject", null)
            if (existingClient != null && !previousSubject.isNullOrBlank() && identity.optString("sub") != previousSubject) {
                error("The ChatGPT account changed during reconnection. Your saved connection was not replaced.")
            }
            val scope = token.optString("scope").ifBlank { error("OpenAI did not return the approved permissions.") }
            val granted = scope.split(' ').toSet()
            if (!granted.contains("resource.invoke") || !granted.contains("chatgpt.tokens.use.direct")) {
                error("ChatGPT sign-in succeeded, but permission to use your ChatGPT plan was not granted. Reconnect and approve plan usage.")
            }
            val accessToken = token.optString("access_token")
            val refreshToken = token.optString("refresh_token")
            if (accessToken.isBlank() || refreshToken.isBlank()) error("OpenAI returned an incomplete account session.")
            prefs.edit()
                .putString("client_id", callback.second)
                .putString("access_token", encrypt(accessToken))
                .putString("refresh_token", encrypt(refreshToken))
                .putString("id_token", encrypt(idToken))
                .putString("scope", scope)
                .putString("email", identity.optString("email"))
                .putString("subject", identity.optString("sub"))
                .putLong("expires_at", System.currentTimeMillis() + token.optLong("expires_in", 3600L) * 1000L)
                .apply()
            status()
        }
    }

    suspend fun listModels(): List<Model> = withContext(Dispatchers.IO) {
        val token = validAccessToken()
        val json = getJson("https://api.openai.com/v1/models", token)
        val array = json.optJSONArray("models") ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (item.optString("visibility") != "list") continue
                val slug = item.optString("slug")
                if (slug.isNotBlank()) add(Model(slug, item.optString("display_name").ifBlank { slug }))
            }
        }
    }

    suspend fun streamResponse(
        prompt: String,
        model: String? = null,
        onChunk: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val token = validAccessToken()
        val selectedModel = model?.takeIf { it.isNotBlank() }
            ?: prefs.getString("model", null)?.takeIf { it.isNotBlank() }
            ?: listModels().firstOrNull()?.slug
            ?: error("No ChatGPT models are available for this account.")
        prefs.edit().putString("model", selectedModel).apply()
        val body = JSONObject()
            .put("model", selectedModel)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", prompt.take(12000))))
            .put("store", false)
            .put("stream", true)
        val connection = (URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
        }
        val full = StringBuilder()
        var completed = false
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                val raw = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val detail = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                if (detail.contains("subscription_sharing_usage_limit_exceeded") ||
                    detail.contains("subscription_sharing_usage_unavailable")) {
                    error("Your ChatGPT plan's shared-app usage limit is unavailable right now. THOR can use its free Gemini fallback.")
                }
                error("ChatGPT request failed (HTTP $statusCode): ${detail.take(180).ifBlank { "please reconnect and retry" }}")
            }
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (!line.startsWith("data:")) return@forEach
                    val data = line.removePrefix("data:").trim()
                    if (data.isBlank() || data == "[DONE]") return@forEach
                    val event = runCatching { JSONObject(data) }.getOrNull() ?: return@forEach
                    when (event.optString("type")) {
                        "response.output_text.delta" -> {
                            val delta = event.optString("delta")
                            if (delta.isNotEmpty()) { full.append(delta); onChunk(delta) }
                        }
                        "response.completed" -> completed = true
                        "response.failed" -> {
                            val err = event.optJSONObject("response")?.optJSONObject("error")
                            val code = err?.optString("code").orEmpty()
                            if (code.contains("subscription_sharing_usage")) {
                                error("Your ChatGPT plan's shared-app usage limit is unavailable. THOR will use its free Gemini fallback.")
                            }
                            error(err?.optString("message")?.take(180).orEmpty().ifBlank { "ChatGPT response failed." })
                        }
                        "error" -> error(event.optString("message").ifBlank { "ChatGPT stream failed." })
                    }
                }
            }
            if (!completed) error("ChatGPT stream ended before completion. Please retry.")
            full.toString().takeIf { it.isNotBlank() } ?: error("ChatGPT returned an empty response.")
        } finally {
            connection.disconnect()
        }
    }

    private fun validAccessToken(): String {
        val expiresAt = prefs.getLong("expires_at", 0L)
        val current = decrypt(prefs.getString("access_token", null))
        if (!current.isNullOrBlank() && System.currentTimeMillis() < expiresAt - 60_000L) return current
        val refresh = decrypt(prefs.getString("refresh_token", null))
            ?: error("Your ChatGPT account is disconnected. Connect it again in THOR settings.")
        val clientId = prefs.getString("client_id", null)
            ?: error("Your ChatGPT account registration is missing. Connect it again.")
        val discovery = getJson(discoveryUrl)
        val token = postForm(discovery.getString("token_endpoint"), linkedMapOf(
            "grant_type" to "refresh_token",
            "client_id" to clientId,
            "refresh_token" to refresh,
            "resource" to resource
        ))
        val access = token.optString("access_token")
        val rotatedRefresh = token.optString("refresh_token").ifBlank { refresh }
        if (access.isBlank()) error("ChatGPT session expired. Please reconnect your account.")
        prefs.edit()
            .putString("access_token", encrypt(access))
            .putString("refresh_token", encrypt(rotatedRefresh))
            .putLong("expires_at", System.currentTimeMillis() + token.optLong("expires_in", 3600L) * 1000L)
            .apply()
        return access
    }

    private fun verifyIdToken(token: String, clientId: String, expectedNonce: String, jwksUri: String): JSONObject {
        val parts = token.split('.')
        if (parts.size != 3) error("OpenAI returned an invalid identity token.")
        val header = JSONObject(String(Base64.decode(parts[0], Base64.URL_SAFE or Base64.NO_WRAP), StandardCharsets.UTF_8))
        val claims = JSONObject(String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP), StandardCharsets.UTF_8))
        if (header.optString("alg") != "RS256") error("Unsupported OpenAI identity signature.")
        val keys = getJson(jwksUri).optJSONArray("keys") ?: error("OpenAI signing keys are unavailable.")
        val key = (0 until keys.length()).mapNotNull { keys.optJSONObject(it) }
            .firstOrNull { it.optString("kid") == header.optString("kid") }
            ?: error("OpenAI identity signing key was not found.")
        val modulus = java.math.BigInteger(1, Base64.decode(key.getString("n"), Base64.URL_SAFE or Base64.NO_WRAP))
        val exponent = java.math.BigInteger(1, Base64.decode(key.getString("e"), Base64.URL_SAFE or Base64.NO_WRAP))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update((parts[0] + "." + parts[1]).toByteArray(StandardCharsets.US_ASCII))
        if (!verifier.verify(Base64.decode(parts[2], Base64.URL_SAFE or Base64.NO_WRAP))) error("OpenAI identity signature verification failed.")
        if (claims.optString("iss") != "https://auth.openai.com") error("OpenAI identity issuer is invalid.")
        val audience = claims.opt("aud")
        val audienceMatches = when (audience) {
            is String -> audience == clientId
            is JSONArray -> (0 until audience.length()).any { audience.optString(it) == clientId }
            else -> false
        }
        if (!audienceMatches) error("OpenAI identity audience is invalid.")
        if (claims.optString("nonce") != expectedNonce) error("OpenAI identity nonce is invalid.")
        if (claims.optLong("exp", 0L) <= System.currentTimeMillis() / 1000L) error("OpenAI identity token has expired.")
        if (claims.optString("sub").isBlank()) error("OpenAI identity is missing its subject.")
        return claims
    }

    private fun getJson(endpoint: String, bearer: String? = null): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("OpenAI sign-in service returned HTTP $code.")
            return JSONObject(raw)
        } finally { connection.disconnect() }
    }

    private fun postFormStatus(endpoint: String, fields: Map<String, String>): Int {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        return try {
            val body = fields.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun postForm(endpoint: String, fields: Map<String, String>): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val body = fields.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(raw).optString("error_description") }.getOrNull().orEmpty()
                error("ChatGPT account authorization failed (HTTP $code): ${detail.take(180).ifBlank { "please try again" }}")
            }
            return JSONObject(raw)
        } finally { connection.disconnect() }
    }

    private fun randomUrlToken(bytes: Int): String {
        val data = ByteArray(bytes)
        SecureRandom().nextBytes(data)
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String?): String? = runCatching {
        if (value.isNullOrBlank()) return null
        val packed = Base64.decode(value, Base64.NO_WRAP)
        val iv = packed.copyOfRange(0, 12)
        val encrypted = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun secretKey(): javax.crypto.SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? javax.crypto.SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(
            keyAlias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
}
