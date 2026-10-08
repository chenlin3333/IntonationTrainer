package com.example.intonationtrainer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.example.intonationtrainer.ui.screens.pitchvisualizer.TrainerScreen
import com.example.intonationtrainer.core.session.MicrophoneSession
import com.example.intonationtrainer.ui.theme.IntonationTrainerTheme

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[TrainerViewModel::class.java] }
    private var permissionPending by mutableStateOf(false)
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change < 0) runOnUiThread { stop("Audio interrupted. Tap Start listening to resume.") }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val stillRequested = permissionPending
        permissionPending = false
        if (stillRequested && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (granted) startCapture()
            else model.fail("Microphone permission is required. Allow it in Settings, then tap Retry.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IntonationTrainerTheme {
                LaunchedEffect(model.microphone.phase) {
                    if(model.microphone.phase==MicrophoneSession.Phase.ERROR) abandonFocus()
                }
                TrainerScreen(model, permissionPending, onToggle = {
                    if(model.microphone.phase==MicrophoneSession.Phase.LISTENING || model.microphone.phase==MicrophoneSession.Phase.STARTING) stop() else requestStart()
                }, onSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) })
            }
        }
    }

    private fun requestStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startCapture()
        else { permissionPending = true; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }

    @Suppress("DEPRECATION")
    private fun startCapture() {
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(focusListener).build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) model.start()
        else model.fail("Audio is busy. Tap Retry when the other audio session ends.")
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        else audioManager.abandonAudioFocus(focusListener)
        focusRequest = null
    }

    private fun stop(message: String = "Ready to listen") { model.stop(message); abandonFocus() }
    override fun onStop() {
        permissionPending = false
        stop("Listening stopped. Tap Start listening to begin again.")
        super.onStop()
    }

}
