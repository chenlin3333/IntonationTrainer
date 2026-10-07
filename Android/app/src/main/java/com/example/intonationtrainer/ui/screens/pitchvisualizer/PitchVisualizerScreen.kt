package com.example.intonationtrainer.ui.screens.pitchvisualizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.intonationtrainer.core.model.PitchFrame

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PitchVisualizerScreen(
    onMicToggleClick: () -> Unit,
    isRecording: Boolean = false,
    currentPitchFrame: PitchFrame? = null,
    recentFrames: List<PitchFrame> = emptyList()
) {
    val isWithinTolerance = currentPitchFrame?.isWithinNoteTolerance(15f) == true

    Surface(modifier = Modifier.fillMaxSize(), tonalElevation = 4.dp) {
        Column(
            modifier = Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            Text(
                text = "Intonation Trainer",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
            )
            Text(
                text = "Pitch Accuracy Analyzer",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
            )

            PitchVisualizer(
                currentFrame = currentPitchFrame,
                recentFrames = recentFrames.takeLast(20),
                isWithinTolerance = isWithinTolerance
            )

            RecordingButton(isRecording = isRecording) { onMicToggleClick() }

        }
    }
}

@Composable
private fun PitchVisualizer(
    currentFrame: PitchFrame?,
    recentFrames: List<PitchFrame>,
    isWithinTolerance: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {

        if (currentFrame != null) {
            PitchDisplay(
                frequency = currentFrame.frequency,
                noteName = currentFrame.noteName,
                deviationCents = currentFrame.deviationCents,
                isWithinTolerance = isWithinTolerance
            )
        } else {
            PlaceholderText("Tap the microphone button to start...")
        }

        if (currentFrame != null) {
            FrequencyBar(frequencyHz = currentFrame.frequency)
        }

        Text(
            text = "Recent Notes:",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp, start = 0.dp, end = 4.dp)
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            items(recentFrames.takeLast(16).reversed()) { frame ->
                NoteHistoryItem(frame = frame, isCurrent = currentFrame == frame)
            }
        }

    }
}

@Composable
private fun PitchDisplay(
    frequency: Float,
    noteName: String,
    deviationCents: Float,
    isWithinTolerance: Boolean
) {
    val statusColor = when {
        abs(deviationCents) < 10f -> Color(0xFF4CAF50)
        abs(deviationCents) < 20f -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }

    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {

        Spacer(modifier = Modifier.width(24.dp))

        Column(modifier = Modifier.weight(1f)) {

            Text("Current Pitch", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).background(statusColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = noteName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Frequency:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("%d Hz".format(frequency.toInt()), style = MaterialTheme.typography.titleMedium)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Deviation:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("%d cents".format(deviationCents.toInt()), style = MaterialTheme.typography.titleMedium, color = statusColor)
                    }
                }
            }

        }

    }
}

@Composable
private fun FrequencyBar(frequencyHz: Float) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainer
    val minFreq = 20f
    val maxFreq = 20000f
    val normalizedPos = ((frequencyHz - minFreq) / (maxFreq - minFreq)).coerceIn(0f, 1f).toFloat()

    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("20 Hz", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
        Spacer(modifier = Modifier.weight(1f))
        Text("%.1f kHz".format(frequencyHz / 1000), style = MaterialTheme.typography.labelSmall)

    }

    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {

        Box(modifier = Modifier.weight(1f).height(24.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                drawRect(color = trackColor, topLeft = Offset(width * 0.8f, 0f), size = Size(width * 0.2f, size.height))
            }
        }

        Box(modifier = Modifier.weight(1f).height(24.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val indicatorWidth = (normalizedPos * width).coerceAtMost(width - 8f)
                drawRect(
                    color = if (frequencyHz < 200f) Color(0xFFFF9800) else Color(0xFF4CAF50),
                    topLeft = Offset(width - indicatorWidth, 0f),
                    size = Size(indicatorWidth, size.height)
                )
            }
        }

    }

}

@Composable
private fun NoteHistoryItem(frame: PitchFrame, isCurrent: Boolean) {
    val noteColor = when {
        abs(frame.deviationCents) < 10f -> Color(0xFF4CAF50)
        else -> Color(0xFFF44336)
    }

    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {

        if (isCurrent) {
            Box(modifier = Modifier.size(10.dp).background(noteColor))
        } else {
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "•", style = MaterialTheme.typography.bodySmall, color = noteColor)
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = frame.noteName,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCurrent) noteColor else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.weight(1f))

        val deviationText = when {
            abs(frame.deviationCents) < 5f -> "On pitch"
            abs(frame.deviationCents) < 20f -> "%d¢ off".format(abs(frame.deviationCents).toInt())
            else -> "%d¢ off".format(abs(frame.deviationCents).toInt())
        }

        Text(
            text = deviationText,
            style = MaterialTheme.typography.bodySmall,
            color = if (abs(frame.deviationCents) < 10f) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(end = 8.dp)
        )

    }
}

@Composable
private fun PlaceholderText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 32.dp)
    )
}

@Composable
private fun RecordingButton(isRecording: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {

        FloatingActionButton(
            onClick = onClick,
            containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            if (isRecording) Icon(Icons.Default.Stop, contentDescription = "Stop recording")
            else Icon(Icons.Default.Mic, contentDescription = "Start recording")
        }

    }
}