package com.thor.assistant

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import java.io.Closeable
import java.io.File
import kotlin.math.sqrt

/**
 * Captures one 5.015-second 16 kHz mono utterance and converts it to a normalized
 * 256-dimensional WeSpeaker speaker embedding. This is an identity feature extractor,
 * not a voice-authentication system by itself.
 */
class ThorSpeakerEngine(context: Context) : Closeable {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val SAMPLE_COUNT = 80_240
        const val EMBEDDING_SIZE = 256
        private const val MODEL_FILE = "wespeaker_emb_fp16.tflite"
    }

    private val appContext = context.applicationContext
    private val fbank = ThorFbank(appContext)
    private val runtime = run {
        val modelFile = File(appContext.filesDir, MODEL_FILE)
        if (!modelFile.exists() || modelFile.length() < 10_000_000L) {
            appContext.assets.open(MODEL_FILE).use { source ->
                modelFile.outputStream().use { target -> source.copyTo(target) }
            }
        }
        require(modelFile.exists() && modelFile.length() > 10_000_000L) {
            "Speaker model is missing. Reinstall the current THOR build."
        }
        val compiled = CompiledModel.create(
            modelFile.absolutePath,
            CompiledModel.Options(Accelerator.CPU),
            null
        )
        Triple(compiled, compiled.createInputBuffers(), compiled.createOutputBuffers())
    }
    private val model get() = runtime.first
    private val inputBuffers get() = runtime.second
    private val outputBuffers get() = runtime.third

    /** Blocks for about 5 seconds. Call from a background thread. */
    fun captureEmbedding(): FloatArray {
        check(ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required for speaker recognition."
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) { "This device cannot open a 16 kHz microphone stream." }

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, SAMPLE_COUNT * 2)
            )
        } catch (error: SecurityException) {
            throw IllegalStateException("Microphone permission was revoked. Grant it and try again.", error)
        }
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "Microphone could not be initialized."
        }

        val pcm16 = ShortArray(SAMPLE_COUNT)
        try {
            recorder.startRecording()
            var offset = 0
            while (offset < pcm16.size) {
                val read = recorder.read(pcm16, offset, pcm16.size - offset)
                check(read > 0) { "Microphone recording stopped unexpectedly." }
                offset += read
            }
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
        }

        val pcm = FloatArray(SAMPLE_COUNT) { pcm16[it] / 32768f }
        val features = fbank.compute(pcm)
        check(features.size == 500 && features.all { it.size == 80 }) {
            "Unexpected speaker feature shape."
        }
        val flat = FloatArray(500 * 80)
        for (frame in 0 until 500) {
            System.arraycopy(features[frame], 0, flat, frame * 80, 80)
        }

        inputBuffers[0].writeFloat(flat)
        model.run(inputBuffers, outputBuffers)
        val embedding = outputBuffers[0].readFloat()
        check(embedding.size == EMBEDDING_SIZE) { "Unexpected speaker model output." }
        var normSquared = 0f
        embedding.forEach { normSquared += it * it }
        val norm = sqrt(normSquared)
        check(norm.isFinite() && norm > 1e-8f) { "Invalid speaker embedding." }
        return FloatArray(embedding.size) { embedding[it] / norm }
    }

    override fun close() {
        inputBuffers.forEach { it.close() }
        outputBuffers.forEach { it.close() }
        model.close()
    }
}

/** Kaldi-compatible log-mel fbank frontend with cepstral mean normalization. */
private class ThorFbank(context: Context) {
    companion object {
        private const val WIN = 400
        private const val HOP = 160
        private const val NFFT = 512
        private const val NBIN = 257
        private const val NMEL = 80
        private const val PREEMPH = 0.97f
        private const val EPS = 1.1920928955078125e-07f
    }

    private val mel = loadFloats(context, "mel80_257.bin", NMEL * NBIN)
    private val hamming = loadFloats(context, "hamming400.bin", WIN)
    private val cosTable = FloatArray(NFFT / 2) {
        kotlin.math.cos(2.0 * Math.PI * it / NFFT).toFloat()
    }
    private val sinTable = FloatArray(NFFT / 2) {
        kotlin.math.sin(2.0 * Math.PI * it / NFFT).toFloat()
    }

    private fun loadFloats(context: Context, name: String, count: Int): FloatArray {
        val bytes = context.assets.open(name).use { it.readBytes() }
        check(bytes.size == count * 4) { "Invalid $name asset size." }
        return FloatArray(count).also { values ->
            java.nio.ByteBuffer.wrap(bytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer()
                .get(values)
        }
    }

    fun compute(pcm: FloatArray): Array<FloatArray> {
        require(pcm.size >= WIN)
        val frameCount = 1 + (pcm.size - WIN) / HOP
        val output = Array(frameCount) { FloatArray(NMEL) }
        val real = FloatArray(NFFT)
        val imag = FloatArray(NFFT)
        val power = FloatArray(NBIN)

        for (frame in 0 until frameCount) {
            var mean = 0f
            val base = frame * HOP
            for (i in 0 until WIN) mean += pcm[base + i]
            mean = mean * 32768f / WIN
            var previous = pcm[base] * 32768f - mean

            for (i in 0 until WIN) {
                val current = pcm[base + i] * 32768f - mean
                real[i] = (current - PREEMPH * previous) * hamming[i]
                imag[i] = 0f
                previous = current
            }
            for (i in WIN until NFFT) {
                real[i] = 0f
                imag[i] = 0f
            }

            fft(real, imag)
            for (bin in 0 until NBIN) {
                power[bin] = real[bin] * real[bin] + imag[bin] * imag[bin]
            }
            for (band in 0 until NMEL) {
                var energy = 0f
                val row = band * NBIN
                for (bin in 0 until NBIN) energy += mel[row + bin] * power[bin]
                output[frame][band] = kotlin.math.ln(maxOf(energy, EPS))
            }
        }

        val means = FloatArray(NMEL)
        for (frame in 0 until frameCount) {
            for (band in 0 until NMEL) means[band] += output[frame][band]
        }
        for (band in 0 until NMEL) means[band] /= frameCount
        for (frame in 0 until frameCount) {
            for (band in 0 until NMEL) output[frame][band] -= means[band]
        }
        return output
    }

    private fun fft(real: FloatArray, imag: FloatArray) {
        var j = 0
        for (i in 0 until NFFT - 1) {
            if (i < j) {
                var value = real[i]; real[i] = real[j]; real[j] = value
                value = imag[i]; imag[i] = imag[j]; imag[j] = value
            }
            var bit = NFFT shr 1
            while (bit in 1..j) {
                j -= bit
                bit = bit shr 1
            }
            j += bit
        }

        var length = 2
        while (length <= NFFT) {
            val half = length shr 1
            val step = NFFT / length
            var start = 0
            while (start < NFFT) {
                var k = 0
                for (index in start until start + half) {
                    val wr = cosTable[k]
                    val wi = -sinTable[k]
                    val xr = real[index + half] * wr - imag[index + half] * wi
                    val xi = real[index + half] * wi + imag[index + half] * wr
                    real[index + half] = real[index] - xr
                    imag[index + half] = imag[index] - xi
                    real[index] += xr
                    imag[index] += xi
                    k += step
                }
                start += length
            }
            length = length shl 1
        }
    }
}
