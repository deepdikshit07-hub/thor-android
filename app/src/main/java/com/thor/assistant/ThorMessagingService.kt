package com.thor.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class ThorMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        getSharedPreferences("thor_master_approval", MODE_PRIVATE)
            .edit().putString("pending_fcm_token", token).apply()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "thor_master_approval") return
        val requestId = message.data["requestId"] ?: return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "thor_master_approval"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                channelId, "THOR Master Mode approvals", NotificationManager.IMPORTANCE_HIGH
            ))
        }
        val intent = Intent(this, MainActivity::class.java)
            .putExtra("thor_approval_request_id", requestId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this, requestId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("THOR Master Mode request")
            .setContentText("Tap to review and approve with your fingerprint.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(requestId.hashCode(), notification)
    }
}
