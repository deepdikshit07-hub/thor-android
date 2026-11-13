package com.thor.assistant

import android.content.Context
import android.os.StatFs
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Local text-generation engine for THOR.
 * The model is stored in private app storage and runs without network access.
 */
class ThorOfflineAi(private val context: Context) {
    companion object {
        const val MODEL_FILE_NAME = "Qwen3-0.6B.litertlm"
        const val MODEL_SIZE_LABEL = "about 586 MB"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm?download=true"
        private const val MIN_FREE_BYTES = 700L * 1024L * 1024L
    }

    private val lock = Mutex()
    private val modelFile = File(File(context.filesDir, "thor-models"), MODEL_FILE_NAME)
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    fun isModelInstalled(): Boolean = modelFile.exists() && modelFile.length() > 100_000_000L
    fun isReady(): Boolean = conversation != null

    suspend fun downloadModel(onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        if (isModelInstalled()) return@withContext

        val directory = modelFile.parentFile ?: error("Cannot access THOR model folder.")
        if (!directory.exists() && !directory.mkdirs()) {
            error("Could not create THOR model folder.")
        }
        val freeBytes = StatFs(context.filesDir.absolutePath).availableBytes
        if (freeBytes < MIN_FREE_BYTES) {
            error("THOR needs at least 700 MB of free storage to install the offline model.")
        }

        val partial = File(directory, "$MODEL_FILE_NAME.part")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "THOR-Android/2.0")
        }

        try {
            connection.connect()
            val status = connection.responseCode
            if (status !in 200..299) error("Model download failed (HTTP $status).")
            val total = connection.contentLengthLong.coerceAtLeast(0L)
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastReported = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (downloaded - lastReported >= 2L * 1024L * 1024L) {
                            onProgress(downloaded, total)
                            lastReported = downloaded
                        }
                    }
                    output.fd.sync()
                    onProgress(downloaded, if (total > 0) total else downloaded)
                }
            }
            if (partial.length() < 100_000_000L) error("The downloaded model file is incomplete.")
            if (modelFile.exists()) modelFile.delete()
            if (!partial.renameTo(modelFile)) error("Could not finalize the downloaded model.")
        } catch (error: Exception) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    suspend fun initialize() = lock.withLock {
        if (conversation != null) return@withLock
        if (!isModelInstalled()) error("Offline model is not installed yet.")

        withContext(Dispatchers.IO) {
            val candidate = Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(),
                    cacheDir = context.cacheDir.absolutePath
                )
            )
            // Do not retain an engine if native initialization fails.
            candidate.initialize()
            val localConversation = candidate.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        "You are THOR, a helpful personal AI assistant. Work fully offline. " +
                            "Understand and reply naturally in English, Hindi, Bengali, and mixed " +
                            "English-Bengali (Banglish). Match the user's language, script, tone, " +
                            "casualness, slang, abbreviations, and level of formality. Understand " +
                            "common and unusual everyday slang from context; do not force slang " +
                            "when the user is formal. Be friendly, clear, accurate, and concise. " +
                            "If unsure, say so instead of inventing facts. You cannot access the " +
                            "internet or control phone features unless THOR's Android app explicitly " +
                            "offers that action."
                    ),
                    samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.7),
                    thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                    maxOutputToken = 256
                )
            )
            engine = candidate
            conversation = localConversation
        }
    }

    suspend fun ask(prompt: String): String = lock.withLock {
        withContext(Dispatchers.IO) {
            val activeConversation = conversation ?: error("Offline AI is still loading.")
            val answer = activeConversation.sendMessage(prompt).toString().trim()
            answer.ifBlank { "I couldn't generate a reply. Try asking in a different way." }
        }
    }

    fun close() {
        try { conversation?.close() } catch (_: Exception) {}
        try { engine?.close() } catch (_: Exception) {}
        conversation = null
        engine = null
    }
}
