package com.thor.assistant

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory
import java.security.PublicKey
import java.util.Base64

/**
 * Device-local signing primitive for THOR's remote approval flow.
 *
 * The private key never leaves Android Keystore. BiometricPrompt authorizes each
 * signing operation. This class deliberately does not contact Firebase or a
 * remote sender; pairing, server authorization, replay protection and session
 * issuance must be completed by the trusted backend before this can unlock Master Mode.
 */
class ThorDeviceApproval(private val activity: FragmentActivity) {
    companion object {
        private const val KEY_ALIAS = "thor_device_approval_v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        fun verifySignature(publicKeyBase64: String, challenge: ByteArray, signatureBytes: ByteArray): Boolean {
            return try {
                val encoded = Base64.getDecoder().decode(publicKeyBase64)
                val publicKey: PublicKey = KeyFactory.getInstance("EC")
                    .generatePublic(X509EncodedKeySpec(encoded))
                Signature.getInstance(SIGNATURE_ALGORITHM).run {
                    initVerify(publicKey)
                    update(challenge)
                    verify(signatureBytes)
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    fun hasKey(): Boolean = try {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        store.containsAlias(KEY_ALIAS)
    } catch (_: Exception) {
        false
    }

    /** Creates a P-256 signing key. Call only on the trusted phone during pairing. */
    fun createKey(): String {
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationValidityDurationSeconds(-1)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
        return publicKeyBase64()
    }

    fun publicKeyBase64(): String {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val certificate = store.getCertificate(KEY_ALIAS)
            ?: throw IllegalStateException("Trusted-device key has not been created.")
        return Base64.getEncoder().encodeToString(certificate.publicKey.encoded)
    }

    /**
     * Displays Android's biometric prompt and signs this one-time server challenge.
     * The caller must send the returned signature to the trusted backend for
     * verification; never unlock locally merely because this callback succeeded.
     */
    fun authenticateAndSign(
        challenge: ByteArray,
        onResult: (Result<ByteArray>) -> Unit
    ) {
        if (challenge.isEmpty() || challenge.size > 4096) {
            onResult(Result.failure(IllegalArgumentException("Invalid approval challenge.")))
            return
        }

        try {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val privateKey = store.getKey(KEY_ALIAS, null) as? java.security.PrivateKey
                ?: throw IllegalStateException("Pair this trusted device before approving requests.")
            val signature = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
                initSign(privateKey)
            }
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        try {
                            val authorizedSignature = result.cryptoObject?.signature
                                ?: throw IllegalStateException("Biometric signing operation was not authorized.")
                            authorizedSignature.update(challenge)
                            onResult(Result.success(authorizedSignature.sign()))
                        } catch (error: Exception) {
                            onResult(Result.failure(error))
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        onResult(Result.failure(IllegalStateException(errString.toString())))
                    }

                    override fun onAuthenticationFailed() {
                        // Android keeps the prompt open for another biometric attempt.
                    }
                }
            )
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Approve THOR Master Mode")
                .setSubtitle("Confirm with your enrolled fingerprint")
                .setDescription("This approves one request on another device.")
                .setNegativeButtonText("Cancel")
                .build()
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(signature))
        } catch (error: Exception) {
            onResult(Result.failure(error))
        }
    }

    fun removeKey() {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
    }
}
