package com.thor.assistant

import android.content.Context
import java.io.File

/**
 * Per-install owner profile. Data remains in this app's private storage.
 * The saved recording is an enrollment sample, not a speaker-verification model.
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

    fun finishSetup() {
        prefs.edit().putBoolean("setup_complete", true).apply()
    }

    fun reset() {
        prefs.edit().clear().apply()
        voiceSampleFile().delete()
    }
}
