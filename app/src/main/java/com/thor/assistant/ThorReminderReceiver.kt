package com.thor.assistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Local reminder alarms. Android may defer inexact alarms under battery restrictions. */
class ThorReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> restorePending(context)
            ACTION_REMIND -> {
                val id = intent.getLongExtra(EXTRA_ID, 0L)
                val message = intent.getStringExtra(EXTRA_TEXT) ?: "THOR reminder"
                if (showNotification(context, id, message)) removeSaved(context, id)
            }
        }
    }

    companion object {
        private const val ACTION_REMIND = "com.thor.assistant.ACTION_REMIND"
        private const val EXTRA_ID = "reminder_id"
        private const val EXTRA_TEXT = "reminder_text"
        private const val PREFS = "thor_reminders"
        private const val KEY_ITEMS = "items"
        private const val CHANNEL = "thor_reminders_channel"

        fun schedule(context: Context, atMillis: Long, message: String): Long {
            val id = System.currentTimeMillis()
            val cleanMessage = message.ifBlank { "THOR reminder" }.take(240)
            val alarmIntent = Intent(context, ThorReminderReceiver::class.java).apply {
                action = ACTION_REMIND
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_TEXT, cleanMessage)
            }
            val pending = PendingIntent.getBroadcast(
                context, id.hashCode(), alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val items = readItems(context)
            items.put(JSONObject().put("id", id).put("at", atMillis).put("text", cleanMessage))
            prefs.edit().putString(KEY_ITEMS, items.toString()).apply()
            return id
        }

        /**
         * Re-arms future reminders after reboot and retries due notifications that
         * could not be displayed because Android notification permission was denied.
         */
        fun restorePending(context: Context) {
            val items = readItems(context)
            val now = System.currentTimeMillis()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val id = item.optLong("id")
                val at = item.optLong("at")
                val message = item.optString("text", "THOR reminder")
                if (at <= now) {
                    if (showNotification(context, id, message)) removeSaved(context, id)
                } else {
                    val alarmIntent = Intent(context, ThorReminderReceiver::class.java).apply {
                        action = ACTION_REMIND
                        putExtra(EXTRA_ID, id)
                        putExtra(EXTRA_TEXT, message)
                    }
                    val pending = PendingIntent.getBroadcast(
                        context, id.hashCode(), alarmIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                        .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                }
            }
        }

        private fun readItems(context: Context): JSONArray {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ITEMS, null) ?: return JSONArray()
            return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        }

        private fun removeSaved(context: Context, id: Long) {
            val old = readItems(context)
            val next = JSONArray()
            for (i in 0 until old.length()) {
                val item = old.optJSONObject(i) ?: continue
                if (item.optLong("id") != id) next.put(item)
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ITEMS, next.toString()).apply()
        }

        private fun showNotification(context: Context, id: Long, message: String): Boolean {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "THOR reminders", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            if (Build.VERSION.SDK_INT >= 26 &&
                manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
            ) return false
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return false

            val openApp = PendingIntent.getActivity(
                context, id.hashCode(),
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(context, CHANNEL)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("THOR reminder")
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build()
            return try {
                manager.notify(id.hashCode(), notification)
                true
            } catch (_: SecurityException) {
                false
            }
        }
    }
}