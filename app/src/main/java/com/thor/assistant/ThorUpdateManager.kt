package com.thor.assistant

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ThorUpdate(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val mandatory: Boolean,
    val releaseNotes: String
)

/**
 * Downloads and verifies official THOR APK releases.
 * Android's package installer remains responsible for installation and user approval.
 */
class ThorUpdateManager(private val activity: Activity) {
    companion object {
        private const val MANIFEST_URL =
            "https://github.com/deepdikshit07-hub/thor-android/releases/latest/download/latest.json"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_APK_BYTES = 1_200L * 1024L * 1024L
    }

    suspend fun checkForUpdate(): ThorUpdate? = withContext(Dispatchers.IO) {
        val connection = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "THOR-Android")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) error("Update server returned HTTP $status.")
            val manifest = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(manifest)
            val versionCode = json.getInt("versionCode")
            val currentVersion = if (Build.VERSION.SDK_INT >= 28) {
                activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode
            }
            if (versionCode <= currentVersion) return@withContext null

            val apkUrl = json.getString("apkUrl")
            val sha256 = json.getString("sha256").lowercase()
            require(apkUrl.startsWith("https://github.com/deepdikshit07-hub/thor-android/releases/download/")) {
                "The update manifest contains an untrusted download location."
            }
            require(sha256.matches(Regex("[0-9a-f]{64}"))) {
                "The update manifest contains an invalid checksum."
            }

            ThorUpdate(
                versionCode = versionCode,
                versionName = json.getString("versionName"),
                apkUrl = apkUrl,
                sha256 = sha256,
                mandatory = json.optBoolean("mandatory", false),
                releaseNotes = json.optString("releaseNotes", "THOR improvements")
            )
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(update: ThorUpdate, onProgress: (Int) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val directory = File(activity.cacheDir, "updates")
            if (!directory.exists() && !directory.mkdirs()) {
                error("Could not create the update download folder.")
            }
            val partial = File(directory, "thor-${update.versionCode}.apk.part")
            val destination = File(directory, "thor-${update.versionCode}.apk")
            val connection = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "THOR-Android")
            }
            try {
                val status = connection.responseCode
                if (status !in 200..299) error("APK download failed (HTTP $status).")
                val total = connection.contentLengthLong
                if (total > MAX_APK_BYTES) error("The update file is larger than the permitted download size.")
                var downloaded = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var lastPercent = -1
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            if (downloaded > MAX_APK_BYTES) error("The update download exceeded the permitted size.")
                            output.write(buffer, 0, count)
                            if (total > 0) {
                                val percent = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    onProgress(percent)
                                    lastPercent = percent
                                }
                            }
                        }
                        output.fd.sync()
                    }
                }
                require(partial.length() > 0L) { "The downloaded update is empty." }
                val digest = MessageDigest.getInstance("SHA-256")
                partial.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                require(actualHash == update.sha256) { "The update checksum does not match. The file was rejected." }
                if (destination.exists()) destination.delete()
                if (!partial.renameTo(destination)) error("Could not finalize the downloaded update.")
                destination
            } catch (error: Exception) {
                partial.delete()
                throw error
            } finally {
                connection.disconnect()
            }
        }

    fun install(apkFile: File): Boolean {
        if (!apkFile.isFile || apkFile.length() == 0L) {
            Toast.makeText(activity, "The update file is missing. Download it again.", Toast.LENGTH_LONG).show()
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(settingsIntent)
            Toast.makeText(
                activity,
                "Allow THOR to install this update, then check for updates again.",
                Toast.LENGTH_LONG
            ).show()
            return false
        }

        val contentUri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(installIntent)
        return true
    }
}
