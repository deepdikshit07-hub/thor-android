package com.thor.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Periodically checks the latest published THOR release and notifies the user.
 * Android still requires the user to approve installation of an APK update.
 */
class ThorUpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val connection = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "THOR-Android")
        }
        return try {
            if (connection.responseCode !in 200..299) return Result.retry()
            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                JSONObject(it.readText())
            }
            val remoteVersion = json.getInt("versionCode")
            val installedVersion = if (Build.VERSION.SDK_INT >= 28) {
                applicationContext.packageManager
                    .getPackageInfo(applicationContext.packageName, 0).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                applicationContext.packageManager
                    .getPackageInfo(applicationContext.packageName, 0).versionCode
            }
            if (remoteVersion <= installedVersion) return Result.success()

            val prefs = applicationContext.getSharedPreferences("thor_ota", Context.MODE_PRIVATE)
            if (prefs.getInt("last_notified_version", 0) >= remoteVersion) return Result.success()
            if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
                return Result.success()
            }
            // Android 13+ treats notification delivery as a runtime permission.
            // A background worker must not prompt; simply skip this notification
            // when the user has not granted permission.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return Result.success()
            }

            createNotificationChannel()
            val openApp = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("thor_open_update_prompt", true)
            }
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                7301,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("THOR update available")
                .setContentText("THOR ${json.optString("versionName")} is ready to download.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "A newer THOR version is available. Tap to review the update and install it."
                ))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            try {
                NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
                // Permission may be revoked between the check and notify().
                return Result.success()
            }
            prefs.edit().putInt("last_notified_version", remoteVersion).apply()
            Result.success()
        } catch (error: Exception) {
            Result.retry()
        } finally {
            connection.disconnect()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "THOR updates",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Notifications when a new THOR version is available" }
            )
        }
    }

    companion object {
        private const val MANIFEST_URL =
            "https://github.com/deepdikshit07-hub/thor-android/releases/latest/download/latest.json"
        private const val CHANNEL_ID = "thor_updates"
        private const val NOTIFICATION_ID = 7301
    }
}
