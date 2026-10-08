package com.example.intonationtrainer.core.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.intonationtrainer.core.session.MicrophoneSession
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import com.example.intonationtrainer.core.practice.AnalysisFrame
import com.example.intonationtrainer.core.practice.PitchPipeline
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Each capture owns its recorder. A single worker releases it before starting another. */
class AudioRecorderSource(private val onAnalysis: (AnalysisFrame) -> Unit = {}) : MicrophoneSession.Capture {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()

    override fun start(onStarted: () -> Unit, onLevel: (Float) -> Unit, onError: (String) -> Unit) {
        val token = generation.incrementAndGet()
        worker.execute {
            var recorder: AudioRecord? = null
            fun post(action: () -> Unit) { main.post { if (generation.get() == token) action() } }
            try {
                if (generation.get() != token) return@execute
                val rate = 48000
                val minimumBytes = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimumBytes > 0) { "This microphone does not support 48 kHz capture." }
                val input = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minimumBytes, 4096))
                recorder = input
                check(input.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be initialized." }
                if (generation.get() != token) return@execute
                input.startRecording()
                check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone could not start." }
                post(onStarted)
                val samples = ShortArray(2048)
                val latest = AtomicReference<AnalysisFrame?>()
                val deliveryPending = AtomicBoolean(false)
                val pipeline = PitchPipeline(input.sampleRate.toDouble()) { frame ->
                    latest.set(frame)
                    if (deliveryPending.compareAndSet(false, true)) {
                        main.post {
                            deliveryPending.set(false)
                            if (generation.get() == token) latest.get()?.let {
                                onLevel(it.levelDb.toFloat())
                                onAnalysis(it)
                            }
                        }
                    }
                }
                var lastRead = System.nanoTime()
                while (generation.get() == token) {
                    if (Build.VERSION.SDK_INT >= 29 && input.activeRecordingConfiguration?.isClientSilenced == true) {
                        error("Microphone was interrupted by another app. Tap Retry when it is available.")
                    }
                    val count = input.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0) { "Microphone capture failed ($count). Tap Retry." }
                    if (count > 0) {
                        lastRead = System.nanoTime()
                        pipeline.accept(FloatArray(count) { samples[it] / 32768f })
                    } else check(System.nanoTime() - lastRead < 500_000_000L) { "Microphone stopped delivering audio. Tap Retry." }
                    Thread.sleep(10)
                }
            } catch (error: Exception) {
                post { onError(error.message ?: "Microphone unavailable. Tap Retry.") }
            } finally {
                recorder?.let { input ->
                    try { if (input.recordingState == AudioRecord.RECORDSTATE_RECORDING) input.stop() }
                    finally { input.release() }
                }
            }
        }
    }

    override fun stop() { generation.incrementAndGet() }
    fun close() { stop(); worker.shutdown() }
}
