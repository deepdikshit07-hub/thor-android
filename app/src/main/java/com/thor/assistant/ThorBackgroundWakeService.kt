package com.thor.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * User-enabled, visible foreground wake listener.
 * It never launches other apps or performs actions from the background.
 * A recognized command is delivered through a notification tap.
 */
class ThorBackgroundWakeService : Service() {
    private var recognizer: SpeechRecognizer? = null
    private var waitingForCommand = false
    private var stopped = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildOngoingNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopped = false
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
            !SpeechRecognizer.isRecognitionAvailable(this)
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext).also { speech ->
                speech.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onError(error: Int) {
                        if (stopped) return
                        Log.w(TAG, "Background recognizer error=$error")
                        scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1600L else 900L)
                    }

                    override fun onResults(results: Bundle?) {
                        if (stopped) return
                        val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim().orEmpty()
                        if (heard.isNotBlank()) {
                            val normalized = heard.lowercase(Locale.ROOT)
                            if (waitingForCommand) {
                                waitingForCommand = false
                                showCommandNotification(heard)
                            } else if (normalized.contains("hey thor") || normalized == "thor") {
                                val command = heard.substringAfter("hey thor", "")
                                    .ifBlank { heard.substringAfter("thor", "") }
                                    .trim().trimStart(',', ':')
                                if (command.isNotBlank()) showCommandNotification(command)
                                else waitingForCommand = true
                            }
                        }
                        scheduleRestart(500L)
                    }
                })
            }
        }
        scheduleRestart(250L)
        return START_STICKY
    }

    private fun scheduleRestart(delay: Long) {
        android.os.Handler(mainLooper).postDelayed({
            if (stopped || recognizer == null) return@postDelayed
            try {
                val language = getSharedPreferences("thor_voice", MODE_PRIVATE)
                    .getString("language", "en-IN") ?: "en-IN"
                val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra("android.speech.extra.PREFER_OFFLINE", true)
                }
                recognizer?.startListening(request)
            } catch (error: Exception) {
                Log.e(TAG, "Could not restart background listening", error)
                scheduleRestart(1800L)
            }
        }, delay)
    }

    private fun showCommandNotification(command: String) {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("thor_background_command", command)
        }
        val pending = PendingIntent.getActivity(
            this, 42, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("THOR heard your command")
            .setContentText(command.take(120).ifBlank { "Tap to open THOR" })
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(COMMAND_NOTIFICATION_ID, notification)
    }

    private fun buildOngoingNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 43, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("THOR background wake is active")
            .setContentText("Microphone listening is enabled. Tap to open THOR.")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "THOR background wake", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        stopped = true
        try { recognizer?.cancel() } catch (_: Exception) {}
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "THOR_BACKGROUND_WAKE"
        private const val CHANNEL_ID = "thor_background_wake"
        private const val NOTIFICATION_ID = 7401
        private const val COMMAND_NOTIFICATION_ID = 7402
    }
}
