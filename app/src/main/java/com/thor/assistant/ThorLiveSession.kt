package com.thor.assistant

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bidirectional Gemini Live audio session.
 *
 * Audio is streamed as 20 ms PCM16 mono chunks (16 kHz) and native model audio
 * is played as PCM16 mono (24 kHz). Nothing is written to chat storage.
 */
class ThorLiveSession(
    context: Context,
    private val listener: Listener
) {
    enum class State { IDLE, CONNECTING, LISTENING, SPEAKING, ERROR }

    interface Listener {
        fun onState(state: State, detail: String)
        fun onTranscript(speaker: String, text: String)
        fun onError(message: String)
    }

    companion object {
        private const val INPUT_RATE = 16_000
        private const val OUTPUT_RATE = 24_000
        private const val FRAME_BYTES = 640 // 20 ms at 16 kHz, mono PCM16
        private const val LIVE_MODEL = "models/gemini-3.8-live"
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopped = AtomicBoolean(false)
    private val audioQueue = ArrayBlockingQueue<ByteArray>(100)
    private var socket: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var previousAudioMode: Int? = null

    suspend fun start(workerUrl: String, voiceName: String, languageName: String) {
        check(socket == null) { "Live session is already started." }
        stopped.set(false)
        notifyState(State.CONNECTING, "SECURING LIVE CONNECTION")
        try {
            val token = ThorLiveTokenClient().create(workerUrl, voiceName)
            // The user may leave Live Mode while the token request is in flight.
            if (stopped.get()) return
            val ready = CompletableDeferred<Unit>()
            val request = Request.Builder()
                .url("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained?access_token=" + java.net.URLEncoder.encode(token, "UTF-8"))
                .build()
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val setup = JSONObject()
                        .put("model", LIVE_MODEL)
                        .put("generationConfig", JSONObject()
                            .put("responseModalities", org.json.JSONArray().put("AUDIO"))
                            .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceName)))))
                        .put("sessionResumption", JSONObject())
                        .put("inputAudioTranscription", JSONObject())
                        .put("outputAudioTranscription", JSONObject())
                        .put("systemInstruction", JSONObject().put("parts", org.json.JSONArray().put(
                            JSONObject().put("text",
                                "You are THOR, a calm, capable, natural voice assistant. Speak conversationally, warmly and concisely. " +
                                "Sound like an original deep, powerful cinematic narrator, not an imitation of any actor or film character. " +
                                "Respond in $languageName unless the user naturally switches language. " +
                                "This is a temporary live conversation. " +
                                "Do not claim to perform actions you have not completed."
                            )
                        )))
                        .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection",
                            JSONObject().put("disabled", false)))
                    webSocket.send(JSONObject().put("setup", setup).toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleServerMessage(text, ready)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!ready.isCompleted) ready.completeExceptionally(t)
                    if (!stopped.get()) fail(t.message ?: "Live connection failed.")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!ready.isCompleted) ready.completeExceptionally(
                        IllegalStateException("Live connection closed before setup completed.")
                    )
                    if (!stopped.get()) fail("Live connection closed: $reason")
                }
            })
            withTimeout(20_000L) { ready.await() }
            if (stopped.get()) return
            startAudio()
            notifyState(State.LISTENING, "LISTENING")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(error.message ?: "Could not start Live Mode.")
            throw error
        }
    }

    private fun handleServerMessage(raw: String, ready: CompletableDeferred<Unit>) {
        try {
            val root = JSONObject(raw)
            if (root.has("setupComplete")) {
                if (!ready.isCompleted) ready.complete(Unit)
                return
            }
            val content = root.optJSONObject("serverContent") ?: return
            if (content.optBoolean("interrupted", false)) {
                audioQueue.clear()
                track?.let { audio ->
                    try { audio.pause(); audio.flush(); audio.play() } catch (_: Exception) {}
                }
                notifyState(State.LISTENING, "LISTENING")
            }
            content.optJSONObject("inputTranscription")?.optString("text")
                ?.takeIf { it.isNotBlank() }?.let { notifyTranscript("YOU", it) }
            content.optJSONObject("outputTranscription")?.optString("text")
                ?.takeIf { it.isNotBlank() }?.let { notifyTranscript("THOR", it) }

            val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (index in 0 until parts.length()) {
                    val inline = parts.optJSONObject(index)?.optJSONObject("inlineData") ?: continue
                    val encoded = inline.optString("data")
                    if (encoded.isNotBlank()) {
                        val bytes = Base64.decode(encoded, Base64.DEFAULT)
                        if (bytes.isNotEmpty()) {
                            audioQueue.offer(bytes)
                            notifyState(State.SPEAKING, "THOR IS SPEAKING")
                        }
                    }
                }
            }
            if (content.optBoolean("turnComplete", false)) {
                notifyState(State.LISTENING, "LISTENING")
            }
        } catch (error: Exception) {
            android.util.Log.w("THOR_LIVE", "Ignored malformed Live message", error)
        }
    }

    private fun startAudio() {
        val minInput = AudioRecord.getMinBufferSize(
            INPUT_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minInput > 0) { "This device does not support a 16 kHz microphone stream." }
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission is required for THOR Live.")
        }
        val input = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minInput, FRAME_BYTES * 4)
            )
        } catch (error: SecurityException) {
            throw IllegalStateException("Android denied THOR access to the microphone. Check app permission and the system microphone privacy switch.", error)
        }
        check(input.state == AudioRecord.STATE_INITIALIZED) {
            input.release()
            "Microphone could not be initialized."
        }
        // Register immediately so every later setup failure releases the recorder.
        recorder = input

        val minOutput = AudioTrack.getMinBufferSize(
            OUTPUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minOutput > 0) { "This device does not support 24 kHz audio playback." }
        val output = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(OUTPUT_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(maxOf(minOutput, OUTPUT_RATE / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        check(output.state == AudioTrack.STATE_INITIALIZED) {
            input.release()
            output.release()
            "Audio output could not be initialized."
        }

        recorder = input
        track = output
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        previousAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        input.startRecording()
        output.play()

        captureJob = scope.launch {
            val frame = ByteArray(FRAME_BYTES)
            while (isActive && !stopped.get()) {
                val count = input.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (count > 0) {
                    val payload = JSONObject().put("realtimeInput", JSONObject().put("audio",
                        JSONObject()
                            .put("mimeType", "audio/pcm;rate=16000")
                            .put("data", Base64.encodeToString(frame.copyOf(count), Base64.NO_WRAP))
                    ))
                    socket?.send(payload.toString())
                } else if (count < 0) {
                    fail("Microphone stream stopped (AudioRecord $count).")
                    break
                }
            }
        }

        playbackJob = scope.launch {
            while (isActive && !stopped.get()) {
                val chunk = audioQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                var offset = 0
                while (offset < chunk.size && isActive && !stopped.get()) {
                    val written = output.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) break
                    offset += written
                }
            }
        }
    }

    fun stop() = releaseResources(State.IDLE, "SESSION ENDED")

    private fun releaseResources(finalState: State, detail: String) {
        if (!stopped.compareAndSet(false, true)) return
        captureJob?.cancel()
        playbackJob?.cancel()
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) {}
        track = null
        audioQueue.clear()
        socket?.close(1000, "THOR Live session ended")
        socket = null
        previousAudioMode?.let { mode ->
            try {
                (appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode = mode
            } catch (_: Exception) {}
        }
        previousAudioMode = null
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        notifyState(finalState, detail)
    }

    private fun fail(message: String) {
        if (stopped.get()) return
        mainHandler.post { listener.onError(message) }
        releaseResources(State.ERROR, "CONNECTION ERROR")
    }

    private fun notifyState(state: State, detail: String) {
        mainHandler.post { listener.onState(state, detail) }
    }

    private fun notifyTranscript(speaker: String, text: String) {
        mainHandler.post { if (!stopped.get()) listener.onTranscript(speaker, text) }
    }
}
