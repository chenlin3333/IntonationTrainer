package com.example.intonationtrainer.core.model

import kotlin.math.abs

/** Represents a single pitch detection result */
data class PitchFrame(
    val timestamp: Long,
    val frequency: Float,
    val noteName: String,
    val deviationCents: Float,
    val confidence: Float,
    val volumeDb: Float
) {
    fun isWithinNoteTolerance(thresholdCents: Float = 15f): Boolean {
        return abs(deviationCents) <= thresholdCents
    }

    fun deviationSemitones(): Float {
        return deviationCents / 100.0f
    }
}

/** Aggregated statistics over a time window */
data class IntonationStats(
    val totalFrames: Int = 0,
    val inTuneCount: Int = 0,
    val outOfTuneCount: Int = 0,
    val avgDeviationCents: Float = 0f,
    val maxDeviationCents: Float = 0f,
    val avgVolumeDb: Float = -60.0f,
    val minFrequency: Float = Float.MAX_VALUE,
    val maxFrequency: Float = 0f,
    val detectedNotes: Set<String> = emptySet(),
    val bpmEstimate: Int? = null
) {
    fun accuracyPercentage(): Float {
        return if (totalFrames == 0) 100f else ((inTuneCount.toFloat() / totalFrames) * 100).toFloat()
    }

    fun getRating(): Char {
        val accuracy = accuracyPercentage()
        return when {
            accuracy >= 95f -> 'S'
            accuracy >= 85f -> 'A'
            accuracy >= 70f -> 'B'
            else -> 'C'
        }
    }

    companion object {
        private val Infinity = Float.MAX_VALUE
    }
}

/** Configuration for the pitch detection pipeline */
data class PitchConfig(
    val targetFrequency: Float = 440.0f,
    val toleranceCents: Float = 15.0f,
    val minVolumeDb: Float = -60.0f,
    val bufferSize: Int = 2048,
    val sampleRate: Float = 48000.0f
)

/** A running stream of pitch data */
class PitchStream(
    private val config: PitchConfig = PitchConfig()
) {

    private var frames: MutableList<PitchFrame> = mutableListOf()
    private var maxHistorySize: Int = 128

    fun addFrame(frame: PitchFrame) {
        if (frames.size >= maxHistorySize) {
            frames.removeAt(0)
        }
        frames.add(frame)
    }

    fun getRecentFrames(count: Int = 16): List<PitchFrame> {
        return frames.takeLast(count).toList()
    }

    fun clear() {
        frames.clear()
    }

    /** Compute statistics over the current buffer */
    fun computeStats(): IntonationStats {
        val nonSilentFrames = frames.filter { it.volumeDb > config.minVolumeDb }

        return if (nonSilentFrames.isEmpty()) {
            IntonationStats(totalFrames = 0)
        } else {
            val deviations = nonSilentFrames.map { abs(it.deviationCents) }.toFloatArray()
            val avgDeviation = deviations.average().toFloat()
            val maxDeviation = deviations.maxOrNull()?.toFloat() ?: 0f

            IntonationStats(
                totalFrames = frames.size,
                inTuneCount = nonSilentFrames.count { abs(it.deviationCents) <= config.toleranceCents },
                outOfTuneCount = nonSilentFrames.size - nonSilentFrames.count { abs(it.deviationCents) <= config.toleranceCents },
                avgDeviationCents = avgDeviation,
                maxDeviationCents = maxDeviation,
                avgVolumeDb = nonSilentFrames.map { it.volumeDb }.average().toFloat(),
                minFrequency = nonSilentFrames.minByOrNull { it.frequency }?.frequency ?: Float.MAX_VALUE,
                maxFrequency = nonSilentFrames.maxByOrNull { it.frequency }?.frequency ?: 0f,
                detectedNotes = nonSilentFrames.map { it.noteName }.toSet()
            )
        }
    }

}