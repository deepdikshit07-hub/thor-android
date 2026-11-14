package com.thor.assistant

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * Offline multilingual speech-to-text. A quantized 32.2 MB model is downloaded
 * once; microphone capture and transcription then run locally.
 */
class ThorWhisperEngine {
    companion object {
        private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin"
        private const val MODEL_SHA256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"
        private const val TARGET_RATE = 16_000
    }

    private val native = ThorWhisperNative()
    private var nativeContext: Long = 0L
    private var loadedModelPath: String? = null
    @Volatile private var cancelRequested = false
    @Volatile private var activeRecorder: AudioRecord? = null

    suspend fun transcribe(filesDir: File, language: String, onStatus: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            cancelRequested = false
            require(language in setOf("en", "hi", "bn")) { "Choose English, Hindi, or Bengali." }
            val model = ensureModel(filesDir, onStatus)
            loadModel(model, onStatus)
            onStatus("Listening… speak now")
            val audio = recordSpeech()
            onStatus("Transcribing on this device…")
            val result = native.nativeTranscribe(nativeContext, audio, language).trim()
            if (result.isBlank()) throw IllegalStateException("I couldn't recognize clear speech. Please try again in a quieter place.")
            result
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ensureModel(filesDir: File, onStatus: (String) -> Unit): File {
        val directory = File(filesDir, "thor_voice")
        if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("THOR couldn't create its voice-model folder.")
        val model = File(directory, "ggml-tiny-q5_1.bin")
        if (model.exists()) {
            if (model.length() > 30_000_000L && sha256(model) == MODEL_SHA256) {
                onStatus("Offline voice model ready")
                return model
            }
            model.delete()
        }
        onStatus("Downloading THOR voice model (about 32 MB, one time)…")
        val partial = File(directory, model.name + ".part")
        partial.delete()
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "THOR-Android/1.0")
            connection.connect()
            if (connection.responseCode !in 200..299) throw IllegalStateException("Voice model download failed (HTTP ${connection.responseCode}). Check internet and retry.")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: 32_200_000L
            BufferedInputStream(connection.inputStream).use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            onStatus("Downloading voice model… ${percent}%")
                            lastPercent = percent
                        }
                    }
                }
            }
        } catch (error: Exception) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
        if (partial.length() < 30_000_000L) {
            partial.delete()
            throw IllegalStateException("The voice model download was incomplete. Please retry.")
        }
        val actual = sha256(partial)
        if (!actual.equals(MODEL_SHA256, ignoreCase = true)) {
            partial.delete()
            throw IllegalStateException("Voice model integrity check failed. Please download it again.")
        }
        if (!partial.renameTo(model)) {
            partial.copyTo(model, overwrite = true)
            partial.delete()
        }
        onStatus("Offline voice model downloaded")
        model
    }

    private fun loadModel(model: File, onStatus: (String) -> Unit) {
        if (loadedModelPath == model.absolutePath && nativeContext != 0L) return
        release()
        onStatus("Loading offline speech engine…")
        nativeContext = native.nativeCreate(model.absolutePath)
        if (nativeContext == 0L) throw IllegalStateException("THOR couldn't load the offline speech model on this device.")
        loadedModelPath = model.absolutePath
    }

    /** Captures raw PCM directly; no Android SpeechRecognizer or cloud STT. */
    private fun recordSpeech(): FloatArray {
        val rates = intArrayOf(16_000, 44_100, 48_000)
        var recorder: AudioRecord? = null
        var actualRate = TARGET_RATE
        for (rate in rates) {
            val minBytes = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBytes <= 0) continue
            try {
                val candidate = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBytes * 2, rate))
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    recorder = candidate
                    actualRate = rate
                    break
                }
                candidate.release()
            } catch (_: Exception) { }
        }
        val input = recorder ?: throw IllegalStateException("This device couldn't open its microphone. Check THOR's microphone permission and the system privacy switch.")
        activeRecorder = input
        val bytes = ByteArrayOutputStream()
        val frame = ShortArray(1024)
        var speechStarted = false
        var silenceMs = 0L
        val startedAt = SystemClock.elapsedRealtime()
        var lastSpeechAt = startedAt
        try {
            input.startRecording()
            while (!cancelRequested && SystemClock.elapsedRealtime() - startedAt < 25_000L) {
                val count = input.read(frame, 0, frame.size)
                if (count < 0) throw IllegalStateException("Microphone capture stopped (AudioRecord ${count}).")
                if (count == 0) continue
                var energy = 0.0
                for (i in 0 until count) {
                    val value = frame[i].toDouble()
                    energy += value * value
                }
                val rms = sqrt(energy / count)
                val now = SystemClock.elapsedRealtime()
                if (rms > 520.0) {
                    speechStarted = true
                    lastSpeechAt = now
                    silenceMs = 0
                } else if (speechStarted) {
                    silenceMs = now - lastSpeechAt
                }
                if (speechStarted) for (i in 0 until count) {
                    val sample = frame[i].toInt()
                    bytes.write(sample and 0xff)
                    bytes.write((sample shr 8) and 0xff)
                }
                if (!speechStarted && now - startedAt > 12_000L) throw IllegalStateException("I didn't hear speech. Check microphone access and try again.")
                if (speechStarted && silenceMs >= 1_200L) break
            }
        } finally {
            try { input.stop() } catch (_: Exception) { }
            input.release()
            activeRecorder = null
        }
        if (cancelRequested) throw IllegalStateException("Voice input stopped because THOR left the screen.")
        val pcm = bytes.toByteArray()
        if (pcm.size < actualRate / 4) throw IllegalStateException("The recording was too short. Please speak a little longer.")
        val source = FloatArray(pcm.size / 2) { index ->
            val low = pcm[index * 2].toInt() and 0xff
            val high = pcm[index * 2 + 1].toInt()
            ((high shl 8) or low).toShort().toFloat() / 32768.0f
        }
        if (actualRate == TARGET_RATE) return source
        val outputCount = (source.size.toLong() * TARGET_RATE / actualRate).toInt().coerceAtLeast(1)
        return FloatArray(outputCount) { index ->
            val position = index.toDouble() * actualRate / TARGET_RATE
            val left = position.toInt().coerceIn(0, source.lastIndex)
            val right = (left + 1).coerceAtMost(source.lastIndex)
            val fraction = (position - left).toFloat()
            source[left] * (1f - fraction) + source[right] * fraction
        }
    }

    fun cancelCapture() {
        cancelRequested = true
        try { activeRecorder?.stop() } catch (_: Exception) { }
    }

    fun release() {
        cancelCapture()
        if (nativeContext != 0L) {
            native.nativeDestroy(nativeContext)
            nativeContext = 0L
        }
        loadedModelPath = null
    }
}
