package com.thor.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Per-install Boss profile. Speaker embeddings are encrypted with a device-bound Android Keystore
 * key; they are intentionally not portable by copying SharedPreferences to another phone.
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
        val encrypted = encrypt(encodedEmbedding)
        prefs.edit().putString("boss_embedding_v1_enc", encrypted).remove("boss_embedding_v1").apply()
    }

    fun bossEmbedding(): String? {
        val encrypted = prefs.getString("boss_embedding_v1_enc", null) ?: return null
        return runCatching { decrypt(encrypted) }.getOrNull()
    }

    fun hasBossEmbedding(): Boolean = !bossEmbedding().isNullOrBlank()

    fun finishSetup() {
        prefs.edit().putBoolean("setup_complete", true).apply()
    }

    fun reset() {
        prefs.edit().clear().apply()
        voiceSampleFile().delete()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        require(combined.size > 12) { "Encrypted speaker profile is incomplete." }
        val iv = combined.copyOfRange(0, 12)
        val ciphertext = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "thor_boss_embedding_aes_v1"
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
