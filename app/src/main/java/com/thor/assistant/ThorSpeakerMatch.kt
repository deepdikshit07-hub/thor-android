package com.thor.assistant

import kotlin.math.sqrt

/**
 * Speaker-embedding enrollment and comparison.
 *
 * This class deliberately contains no guessed model threshold. A threshold must be calibrated
 * with positive and negative recordings from the target device before enabling automatic labels.
 */
class ThorSpeakerMatch(
    private val embeddingSize: Int = 256
) {
    data class MatchResult(val similarity: Float, val matched: Boolean)

    fun createBossTemplate(samples: List<FloatArray>): FloatArray {
        require(samples.size >= 3) { "Record at least three separate enrollment samples." }
        require(samples.all { it.size == embeddingSize }) { "Unexpected speaker embedding size." }

        val mean = FloatArray(embeddingSize)
        samples.forEach { sample ->
            val normalized = normalize(sample)
            for (i in mean.indices) mean[i] += normalized[i]
        }
        return normalize(mean)
    }

    fun compare(
        candidate: FloatArray,
        bossTemplate: FloatArray,
        calibratedThreshold: Float
    ): MatchResult {
        require(candidate.size == embeddingSize && bossTemplate.size == embeddingSize) {
            "Unexpected speaker embedding size."
        }
        require(calibratedThreshold in -1f..1f) { "Threshold must be calibrated to a cosine value." }

        val a = normalize(candidate)
        val b = normalize(bossTemplate)
        var similarity = 0f
        for (i in a.indices) similarity += a[i] * b[i]
        return MatchResult(similarity, similarity >= calibratedThreshold)
    }

    fun encode(embedding: FloatArray): String {
        require(embedding.size == embeddingSize)
        val normalized = normalize(embedding)
        val bytes = java.nio.ByteBuffer.allocate(embeddingSize * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        normalized.forEach { bytes.putFloat(it) }
        return android.util.Base64.encodeToString(bytes.array(), android.util.Base64.NO_WRAP)
    }

    fun decode(encoded: String): FloatArray? = runCatching {
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        require(bytes.size == embeddingSize * 4)
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        normalize(FloatArray(embeddingSize) { buffer.float })
    }.getOrNull()

    private fun normalize(input: FloatArray): FloatArray {
        var normSquared = 0f
        input.forEach { normSquared += it * it }
        val norm = sqrt(normSquared)
        require(norm > 1e-8f && norm.isFinite()) { "Invalid or silent speaker embedding." }
        return FloatArray(input.size) { input[it] / norm }
    }
}
