package com.thor.assistant

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Local Master passcode verifier. Only a salted PBKDF2 hash is persisted; the passcode is never stored.
 * This is a per-install verifier until an authenticated cross-device Master account is implemented.
 */
class ThorMasterPasscode(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("thor_master_auth", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    fun isConfigured(): Boolean =
        prefs.contains("salt_v1") && prefs.contains("hash_v1")

    fun setPasscode(passcode: CharArray) {
        require(passcode.size >= 6) { "Passcode must contain at least 6 characters." }
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = derive(passcode, salt)
        prefs.edit()
            .putString("salt_v1", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash_v1", Base64.encodeToString(hash, Base64.NO_WRAP))
            .remove("failed_attempts")
            .remove("locked_until")
            .apply()
        passcode.fill('\u0000')
    }

    fun verify(passcode: CharArray): Boolean {
        if (!isConfigured() || isLockedOut()) {
            passcode.fill('\u0000')
            return false
        }
        return try {
            val salt = Base64.decode(prefs.getString("salt_v1", "") ?: "", Base64.NO_WRAP)
            val expected = Base64.decode(prefs.getString("hash_v1", "") ?: "", Base64.NO_WRAP)
            val actual = derive(passcode, salt)
            val valid = MessageDigest.isEqual(expected, actual)
            if (valid) {
                prefs.edit().remove("failed_attempts").remove("locked_until").apply()
            } else {
                recordFailure()
            }
            valid
        } finally {
            passcode.fill('\u0000')
        }
    }

    fun isLockedOut(): Boolean =
        System.currentTimeMillis() < prefs.getLong("locked_until", 0L)

    fun remainingLockoutSeconds(): Long =
        ((prefs.getLong("locked_until", 0L) - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1000L

    private fun recordFailure() {
        val attempts = prefs.getInt("failed_attempts", 0) + 1
        val editor = prefs.edit().putInt("failed_attempts", attempts)
        if (attempts >= MAX_ATTEMPTS) {
            editor.putLong("locked_until", System.currentTimeMillis() + LOCKOUT_MS)
                .putInt("failed_attempts", 0)
        }
        editor.apply()
    }

    private fun derive(passcode: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passcode, salt, PBKDF2_ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private companion object {
        const val PBKDF2_ITERATIONS = 210_000
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MS = 30_000L
    }
}
