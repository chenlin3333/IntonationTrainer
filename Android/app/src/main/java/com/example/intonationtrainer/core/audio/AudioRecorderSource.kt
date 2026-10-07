package com.example.intonationtrainer.core.audio

import com.example.intonationtrainer.core.pitchdetector.AutocorrelationPitchDetector
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.thread

/**
 * Captures audio from the device microphone and yields short-time audio buffers.
 */
class AudioRecorderSource(
    private val sampleRate: Int = 48000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_STEREO,
    private val bufferSize: Int = 2048
) {

    companion object {
        const val TAG = "AudioRecorderSource"

    }

    private fun validateBufferSize(bufferSize: Int): Int {
        val minimum = AudioRecord.getMinBufferSize(
            sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT
        )
        require(minimum > 0) { "Unsupported audio recording configuration" }
        return bufferSize.coerceAtLeast(minimum)
    }

    private var audioRecord: AudioRecord? = try {
        val actualBufferSize = validateBufferSize(bufferSize)
        AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            actualBufferSize
        ).apply {
            check(state == AudioRecord.STATE_INITIALIZED) { "Audio record not initialized" }
        }
    } catch (e: SecurityException) {
        null
    } catch (e: Exception) {
        null
    }

    private val _audioBuffers = MutableStateFlow<List<ShortArray>>(emptyList())
    val audioBuffers = _audioBuffers.asStateFlow()

    var isRecording: Boolean = false

    fun startRecording(): Result<Unit> {
        val audioRecord = audioRecord
        if (audioRecord == null) return Result.failure(RuntimeException("Audio record not initialized"))
        try {
            audioRecord.startRecording()
            isRecording = true
            _audioBuffers.value = emptyList()
            thread(name = "AudioCaptureThread") {
                processAudioStream()
            }
            return Result.success(Unit)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    private fun processAudioStream() {
        val buffer = ShortArray(bufferSize)

        while (isRecording && !Thread.currentThread().isInterrupted) {
            try {
                val readCount: Int = audioRecord?.read(buffer, 0, bufferSize) ?: -1
                if (readCount > 0) {
                    _audioBuffers.value = listOf(buffer.copyOf(readCount))
                } else if (readCount < 0) {
                    Thread.currentThread().interrupt()
                    break
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    fun stopRecording(): Result<Unit> {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        _audioBuffers.value = emptyList()
        return Result.success(Unit)
    }

    fun getLatestBuffer(): ShortArray? {
        val buffers = _audioBuffers.value
        return if (buffers.isEmpty()) null else buffers.lastOrNull()
    }

}

/** Helper to convert pitch detector result to PitchFrame */
object PitchFrameConverter {

    fun createPitchFrame(
        samples: ShortArray,
        sampleRate: Float = 48000.0f,
        volumeDb: Float = -60.0f,
        detector: AutocorrelationPitchDetector
    ): com.example.intonationtrainer.core.model.PitchFrame {
        val analysisResult = detector.detectPitch(samples, sampleRate)

        if (analysisResult.frequency <= 0f) {
            return com.example.intonationtrainer.core.model.PitchFrame(
                timestamp = System.currentTimeMillis(),
                frequency = 0f,
                noteName = "Silence",
                deviationCents = -1f,
                confidence = 0f,
                volumeDb = volumeDb
            )
        }

        val noteAnalysis = detector.analyzeNote(analysisResult.frequency)

        return com.example.intonationtrainer.core.model.PitchFrame(
            timestamp = System.currentTimeMillis(),
            frequency = analysisResult.frequency,
            noteName = noteAnalysis.noteName,
            deviationCents = noteAnalysis.deviationCents,
            confidence = analysisResult.confidence,
            volumeDb = volumeDb
        )
    }

}