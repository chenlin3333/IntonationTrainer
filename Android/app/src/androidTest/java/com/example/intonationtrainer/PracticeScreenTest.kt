package com.example.intonationtrainer

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Exercises real Compose navigation without granting microphone permission. */
class PracticeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun allModesTargetsAndScoreRecoveryAreReachable() {
        compose.onNodeWithText("Practice mode: Free tuning").assertExists().performClick()
        compose.onNodeWithText("Selected note",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Target note: A4").assertExists().performClick()
        compose.onNodeWithText("C4",useUnmergedTree=true).performScrollTo().performClick()
        compose.onNodeWithText("Target note: C4").assertExists()
        compose.onNodeWithText("Successful holds: 0").assertExists()
        screenshot("selected-note")
        compose.onNodeWithText("Practice mode: Selected note").performClick()
        compose.onNodeWithText("Follow score",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Exercise: C major scale").performClick()
        compose.onNodeWithText("Repeated notes",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Next: A4 · 1/8").assertExists()
        compose.onNodeWithText("Next",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("Next: A4 · 2/8").assertExists()
        compose.onNodeWithText("Restart").performClick()
        compose.onNodeWithText("Next: A4 · 1/8").assertExists()
        screenshot("score-following")
    }
    private fun screenshot(name:String) {
        val file=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"$name.png")
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
    }
}
