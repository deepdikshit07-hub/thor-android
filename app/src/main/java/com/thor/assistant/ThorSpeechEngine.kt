package com.thor.assistant

import android.content.Context
import android.os.StatFs
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min

/**
 * THOR's fully local speech-to-text engine.
 *
 * Audio is captured by Android AudioRecord in THOR and decoded by Sherpa-ONNX.
 * No Android SpeechRecognizer service or external recognition activity is used.
 */
class ThorSpeechEngine(context: Context) {
    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val MODEL_BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/"
        private const val MIN_FREE_BYTES = 220L * 1024L * 1024L
        private const val EXPECTED_MODEL_BYTES = 100L * 1024L * 1024L
    }

    private val modelDirectory = File(context.filesDir, "thor-speech/whisper-tiny")
    private val encoderFile = File(modelDirectory, "tiny-encoder.int8.onnx")
    private val decoderFile = File(modelDirectory, "tiny-decoder.int8.onnx")
    private val tokensFile = File(modelDirectory, "tiny-tokens.txt")

    private var recognizer: OfflineRecognizer? = null
    private var recognizerLanguage: String? = null

    fun isInstalled(): Boolean =
        encoderFile.length() > 5L * 1024L * 1024L &&
            decoderFile.length() > 40L * 1024L * 1024L &&
            tokensFile.length() > 100_000L

    suspend fun ensureInstalled(onProgress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            if (isInstalled()) return@withContext

            val freeBytes = StatFs(modelDirectory.parentFile?.absolutePath
                ?: modelDirectory.absolutePath).availableBytes
            if (freeBytes < MIN_FREE_BYTES) {
                error("THOR needs at least 220 MB of free storage for its offline speech model.")
            }
            if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
                error("THOR could not create its private speech-model folder.")
            }

            val files = listOf(
                Triple("tiny-encoder.int8.onnx", encoderFile, 5L * 1024L * 1024L),
                Triple("tiny-decoder.int8.onnx", decoderFile, 40L * 1024L * 1024L),
                Triple("tiny-tokens.txt", tokensFile, 100_000L)
            )
            try {
                for ((name, target, minimumSize) in files) {
                    if (target.isFile && target.length() >= minimumSize) continue
                    downloadFile("$MODEL_BASE$name?download=true", target, minimumSize)
                    val completed = files.sumOf { it.second.length().coerceAtLeast(0L) }
                    onProgress(completed.coerceAtMost(EXPECTED_MODEL_BYTES), EXPECTED_MODEL_BYTES)
                }
                check(isInstalled()) { "The downloaded speech model is incomplete. Please retry on a stable connection." }
                onProgress(EXPECTED_MODEL_BYTES, EXPECTED_MODEL_BYTES)
            } catch (error: Exception) {
                if (!isInstalled()) {
                    encoderFile.takeIf { it.length() < 5L * 1024L * 1024L }?.delete()
                    decoderFile.takeIf { it.length() < 40L * 1024L * 1024L }?.delete()
                    tokensFile.takeIf { it.length() < 100_000L }?.delete()
                }
                throw error
            }
        }

    private fun downloadFile(url: String, target: File, minimumSize: Long) {
        val partial = File(target.parentFile, target.name + ".part")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 90_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "THOR-Android/2.1")
        }
        try {
            connection.connect()
            val status = connection.responseCode
            if (status !in 200..299) error("Speech-model download failed (HTTP $status).")
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(partial.length() >= minimumSize) {
                "A speech-model file downloaded incompletely. Please retry."
            }
            if (target.exists() && !target.delete()) error("Could not replace an incomplete speech-model file.")
            if (!partial.renameTo(target)) error("Could not save the downloaded speech-model file.")
        } catch (error: Exception) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun transcribe(samples: FloatArray, languageCode: String): String {
        check(isInstalled()) { "The offline speech model has not been installed yet." }
        require(samples.size >= SAMPLE_RATE / 2) { "Please speak for a little longer and try again." }

        val active = if (recognizer == null || recognizerLanguage != languageCode) {
            closeRecognizer()
            val modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoderFile.absolutePath,
                    decoder = decoderFile.absolutePath,
                    language = languageCode,
                    task = "transcribe"
                ),
                tokens = tokensFile.absolutePath,
                modelType = "whisper",
                numThreads = max(1, min(4, Runtime.getRuntime().availableProcessors() / 2)),
                provider = "cpu"
            )
            OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = modelConfig
                )
            ).also {
                recognizer = it
                recognizerLanguage = languageCode
            }
        } else {
            recognizer!!
        }

        val stream = active.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
            active.decode(stream)
            active.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun close() {
        closeRecognizer()
    }

    private fun closeRecognizer() {
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
        recognizerLanguage = null
    }
}
