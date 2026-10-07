package com.example.intonationtrainer.core.pitchdetector

import android.media.AudioFormat
import android.media.AudioRecord
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Real-time pitch detector using autocorrelation algorithm.
 * Efficient, works on monophonic audio streams (single note at a time).
 */
class AutocorrelationPitchDetector {

    companion object {
        private const val MIN_FREQUENCY = 20f
        private const val MAX_FREQUENCY = 6000f
        private const val BUFFER_SIZE = 4096
    }

    /** Detect pitch from short-time audio samples. */
    fun detectPitch(audioSamples: ShortArray, sampleRate: Float): PitchAnalysisResult {
        if (audioSamples.isEmpty()) return PitchAnalysisResult(0f, -1f, 0f)

        val numChannels = 2
        val minBufferLength = BUFFER_SIZE * numChannels
        if (audioSamples.size < minBufferLength) {
            return PitchAnalysisResult(0f, audioSamples.size.toFloat(), -1f)
        }

        val bufferSize = min(audioSamples.size / numChannels, 256).toInt()
        if (bufferSize < BUFFER_SIZE) return PitchAnalysisResult(0f, 0f, 0f)

        val leftChannel = audioSamples.copyOfRange(0, audioSamples.size / numChannels)
        val rightChannel = audioSamples.copyOfRange(numChannels, audioSamples.size / numChannels * numChannels)

        // Use average of both channels for monophonic analysis
        val combined = FloatArray(bufferSize) { i ->
            (leftChannel[i] + rightChannel[i]) / 2f
        }

        return detectFromSamples(combined, sampleRate)
    }

    private fun detectFromSamples(samples: FloatArray, sampleRate: Float): PitchAnalysisResult {
        if (samples.isEmpty()) return PitchAnalysisResult(0f, -1f, 0f)

        val fftSize = samples.size.coerceAtLeast(BUFFER_SIZE).coerceAtMost(256)
        val windowedSamples = applyWindow(samples.copyOfRange(0, fftSize))

        val realPart = FloatArray(fftSize / 2 + 1) { it.toFloat() }
        val imagPart = FloatArray(fftSize / 2 + 1) { -it.toFloat() }

        // Simple FFT using Cooley-Tukey (radix-2, iterative)
        fft(realPart, imagPart, samples.size.coerceAtLeast(8).coerceAtMost(64))

        val magnitude = FloatArray(fftSize / 2 + 1) { i ->
            sqrt(sqr(realPart[i]) + sqr(imagPart[i]))
        }

        // Step 2: Apply spectral envelope smoothing
        val smoothedMagnitude = smoothSpectrum(magnitude, fftSize / 4)

        // Step 3: Compute autocorrelation of magnitude spectrum
        val n = smoothedMagnitude.size
        val r = FloatArray(n) { i -> if (i == 0) 1.0f else 0f }

        for (lag in 0 until min(n - 2, n / 2)) {
            var sum = 0f
            for (j in lag until n) {
                sum += smoothedMagnitude[j] * smoothedMagnitude[j + lag]
            }
            r[lag] = sum
        }

        // Step 4: Find the first peak after lag=1
        val maxLag = min(n - 2, 1024)
        var bestLag = 1
        var bestCorrelation = 0f

        for (lag in 1 until maxLag) {
            if (r[lag] > bestCorrelation && r[lag] > r[lag + min(1, maxLag - lag)] ||
                r[lag] > bestCorrelation && r[lag] > r[lag - 1]) {
                bestCorrelation = r[lag]
                bestLag = lag
            }
        }

        // Step 5: Calculate frequency from the lag
        val estimatedFrequency = sampleRate / (bestLag.toFloat() * (fftSize / samples.size).toFloat())

        return PitchAnalysisResult(
            frequency = max(MIN_FREQUENCY, min(MAX_FREQUENCY, estimatedFrequency)),
            confidence = bestCorrelation.coerceIn(0f, 1f),
            lag = bestLag.toFloat()
        )
    }

    private fun applyWindow(samples: FloatArray): FloatArray {
        val windowed = FloatArray(samples.size)
        val n = samples.size

        for (i in 0 until n) {
            windowed[i] = samples[i] * hamming(i, n)
        }
        return windowed
    }

    private fun hamming(n: Int): FloatArray {
        return FloatArray(n) { i ->
            0.54f - 0.46f * cos((2 * PI * i) / (n - 1)).toFloat()
        }
    }

    private fun hamming(i: Int, n: Int): Float {
        return 0.54f - 0.46f * cos((2 * PI * i) / (n - 1)).toFloat()
    }

    private fun smoothSpectrum(magnitude: FloatArray, windowSize: Int): FloatArray {
        val result = FloatArray(magnitude.size)
        for (i in magnitude.indices) {
            var sum = 0f
            var count = 0f
            val start = max(0, i - windowSize / 2)
            val end = min(magnitude.size - 1, i + windowSize / 2)

            for (j in start until end) {
                val weight = gaussian(abs(i - j).toFloat(), windowSize.toFloat())
                sum += magnitude[j] * weight
                count += weight
            }

            if (count > 0.001f) {
                result[i] = sum / count
            } else {
                result[i] = magnitude[i]
            }
        }
        return result
    }

    private fun gaussian(x: Float, sigma: Float): Float {
        if (sigma <= 0.1f) return 1f.coerceAtMost(0.5f / abs(x))
        val t = x / sigma
        return exp(-t * t / 2.0f)
    }

    private fun sqr(x: Float): Float {
        return x * x
    }

    // Simple in-place FFT (Cooley-Tukey radix-2 DIT)
    private var n: Int = -1
    private var bits: Int = -1
    private var bitRevTable: ShortArray? = null

    private fun fft(r: FloatArray, i: FloatArray, nfft: Int) {
        if (nfft != n || n != 2 shl bits) {
            initFFT(nfft)
        }
        var j = 0
        for (ii in r.indices) {
            val k = requireNotNull(bitRevTable)[ii]
            while (j < k) {
                var tempReal = r[j + 1]; r[j + 1] = r[j]; r[j] = tempReal
                val ti = i[j + 1]; i[j + 1] = i[j]; i[j] = ti
                j += 2 shl bits - n
            }
            j++
        }

        var len = 2
        while (len <= nfft) {
            for (k in 0 until nfft step len) {
                val halfLen = len / 2
                val angleStep = (2 * PI / len).toFloat()
                var wReal = 1f; var wImag = 0f

                for (jj in k until k + halfLen) {
                    val tR = wReal * i[jj + halfLen] - wImag * r[jj + halfLen]
                    val tI = wReal * i[jj + halfLen] + wImag * r[jj + halfLen]

                    i[jj + halfLen] = i[jj] + tR
                    r[jj + halfLen] = r[jj] + tI
                    i[jj] = i[jj] - tR
                    r[jj] = r[jj] - tI

                    val tempRe = wReal * cos(angleStep) - wImag * sin(angleStep)
                    val tempIm = wReal * sin(angleStep) + wImag * cos(angleStep)
                    wReal = tempRe; wImag = tempIm
                }
            }
            len *= 2
        }

        for (k in r.indices) {
            r[k] /= nfft.toFloat()
            i[k] /= nfft.toFloat()
        }
    }

    private fun initFFT(n: Int) {
        val logN = log2(n.toFloat()).toInt()
        if (n != 1 shl logN) return

        this.n = n
        bits = logN
        bitRevTable = ShortArray(1 shl logN) { ii ->
            var value: Int = ii; var shift: Int = 0
            while (value > 1) {
                value = value shr 1
                shift++
            }
            value.toShort()
        }
    }

    private fun log2f(x: Float): Float {
        return log2(x)
    }

    /** Result of a single pitch detection frame */
    data class PitchAnalysisResult(
        val frequency: Float,
        val confidence: Float,
        val lag: Float
    )

    fun analyzeNote(frequency: Float): NoteAnalysis {
        if (frequency <= 0f) return NoteAnalysis(0, "Silence", 0f, -1f)

        val A4 = 440.0f
        val MIDI_A4 = 69

        val semitonesFromA4 = 12 * log2(frequency / A4)
        val roundedSemitones = floor(semitonesFromA4 + 0.5f).toInt()
        var midiNote: Int = MIDI_A4 + roundedSemitones.toInt()
        midiNote = max(24, min(108, midiNote))

        val noteName = getNoteName(midiNote)
        val centsDeviation = ((semitonesFromA4 - roundedSemitones.toFloat()) * 100).toFloat()

        return NoteAnalysis(
            midiNoteNumber = midiNote,
            noteName = noteName,
            frequency = frequency,
            deviationCents = centsDeviation
        )
    }

    private fun getNoteName(midi: Int): String {
        val notes = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val octave = (midi - 12) / 12
        val noteIndex = (midi - 12) % 12
        return notes[noteIndex] + octave
    }

}

data class NoteAnalysis(
    val midiNoteNumber: Int,
    val noteName: String,
    val frequency: Float,
    val deviationCents: Float
) {
    companion object {
        val A4 = 440f
        val MIDI_A4 = 69
    }
}
