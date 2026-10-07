package com.example.intonationtrainer

import kotlin.concurrent.thread
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.intonationtrainer.core.audio.AudioRecorderSource
import com.example.intonationtrainer.core.audio.PitchFrameConverter
import com.example.intonationtrainer.core.model.PitchStream
import com.example.intonationtrainer.core.pitchdetector.AutocorrelationPitchDetector
import com.example.intonationtrainer.ui.screens.pitchvisualizer.PitchVisualizerScreen
import com.example.intonationtrainer.ui.theme.IntonationTrainerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            IntonationTrainerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PitchVisualizerScreen(
                        onMicToggleClick = { toggleRecording() },
                        isRecording = isRecording,
                        currentPitchFrame = currentPitchFrame,
                        recentFrames = recentFrames
                    )
                }
            }
        }
    }

    // --- State & Lifecycle ---

    private val _isRecording = mutableStateOf(false)
    var isRecording: Boolean
        get() = _isRecording.value
        set(value) { _isRecording.value = value }

    private val _currentFrame = mutableStateOf<com.example.intonationtrainer.core.model.PitchFrame?>(null)
    var currentPitchFrame: com.example.intonationtrainer.core.model.PitchFrame?
        get() = _currentFrame.value
        set(value) { _currentFrame.value = value }

    private val _recentFrames = mutableStateOf<List<com.example.intonationtrainer.core.model.PitchFrame>>(emptyList())
    var recentFrames: List<com.example.intonationtrainer.core.model.PitchFrame>
        get() = _recentFrames.value
        set(value) { _recentFrames.value = value }

    // --- Audio Pipeline Setup ---

    private val audioRecorderSource by lazy {
        AudioRecorderSource(
            sampleRate = 48000,
            channelConfig = android.media.AudioFormat.CHANNEL_IN_STEREO,
            bufferSize = 2048
        )
    }

    private val pitchDetector by lazy { AutocorrelationPitchDetector() }
    private val pitchStream = PitchStream()

    // --- Permission Handling ---

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.filter { it.value }.size >= 1
        if (granted) startRecording() else {}
    }

    private fun requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        } else {
            startRecording()
        }
    }

    private fun toggleRecording() {
        if (isRecording) stopRecording() else requestPermissions()
    }

    private fun startRecording() {
        val result = audioRecorderSource.startRecording()
        if (!result.isSuccess) {}
        _isRecording.value = true
    }

    private fun stopRecording() {
        val result = audioRecorderSource.stopRecording()
        if (result.isSuccess) {
            _isRecording.value = false
            pitchStream.clear()
            _currentFrame.value = null
            _recentFrames.value = emptyList()
        }
    }

    // --- Audio Processing Thread ---

    private val processingThread by lazy { thread(name = "PitchDetectionWorker") { processAudioLoop() } }

    private fun processAudioLoop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val latestBuffer = audioRecorderSource.getLatestBuffer() ?: continue
                if (latestBuffer.isEmpty()) continue

                val analysisResult = pitchDetector.detectPitch(latestBuffer, 48000f)
                val frame = PitchFrameConverter.createPitchFrame(
                    samples = latestBuffer.copyOf(),
                    sampleRate = 48000.0f,
                    volumeDb = calculateVolumeDb(latestBuffer),
                    detector = pitchDetector
                )

                pitchStream.addFrame(frame)
                _currentFrame.value = frame
                if (_recentFrames.value.size < 64) {
                    _recentFrames.value = _recentFrames.value + listOf(frame)
                } else {
                    val updated = _recentFrames.value.toMutableList()
                    updated.removeAt(0)
                    updated.add(frame)
                    _recentFrames.value = updated
                }

            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun calculateVolumeDb(samples: ShortArray): Float {
        if (samples.isEmpty()) return -60f
        var sumSquared = 0.0
        for (sample in samples) {
            val normalized = sample.toFloat() / 32768.0f
            sumSquared += normalized * normalized
        }
        val rms = kotlin.math.sqrt(sumSquared / samples.size.toDouble()).toFloat()
        return (-20.0f * kotlin.math.log10(rms) + 94.0f).coerceIn(-90f, 0f)
    }

}