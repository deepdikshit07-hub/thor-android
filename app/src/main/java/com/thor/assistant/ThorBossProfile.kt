package com.thor.assistant

import android.content.Context
import java.io.File

/**
 * Per-install owner profile. Voice material remains in app-private storage.
 * The original recording is an enrollment source; a verified speaker embedding is stored
 * separately once the on-device model pipeline is connected.
 */
class ThorBossProfile(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("thor_boss_profile", Context.MODE_PRIVATE)

    fun isConfigured(): Boolean = prefs.getBoolean("setup_complete", false)
    fun bossName(): String = prefs.getString("boss_name", "") ?: ""

    fun saveBossName(name: String) {
        prefs.edit().putString("boss_name", name.trim()).apply()
    }

    fun voiceSampleFile(): File = File(appContext.filesDir, "thor_boss_voice_sample.m4a")

    fun hasVoiceSample(): Boolean = voiceSampleFile().exists() && voiceSampleFile().length() > 0L

    fun saveBossEmbedding(encodedEmbedding: String) {
        require(encodedEmbedding.isNotBlank())
        prefs.edit().putString("boss_embedding_v1", encodedEmbedding).apply()
    }

    fun bossEmbedding(): String? = prefs.getString("boss_embedding_v1", null)

    fun hasBossEmbedding(): Boolean = !bossEmbedding().isNullOrBlank()

    fun finishSetup() {
        prefs.edit().putBoolean("setup_complete", true).apply()
    }

    fun reset() {
        prefs.edit().clear().apply()
        voiceSampleFile().delete()
    }
}
