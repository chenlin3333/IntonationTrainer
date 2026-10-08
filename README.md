# 🎵 Intonation Trainer

A mobile app that helps musicians practice intonation by showing the note they are playing and how sharp or flat it is.

## Project Status

Both Android and iOS now implement free tuning, selected-note exercises, score following for three bundled exercises, automatic note-event segmentation, and a rolling history of completed notes. Microphone capture, permission handling, interruption recovery, and on-device pitch analysis are connected to the UI.

The Kotlin and Swift engines run shared deterministic fixtures. Software implementation is complete for the scoped features; **physical-device latency, real-instrument behavior, and interruption checks are still release gates**, so the MVP is not yet declared validated for release. See [validation evidence and remaining checks](docs/VALIDATION.md).

**Release requirement:** the MVP includes both Android and iOS, with equivalent tuning, selected-note exercises, score following, and automatic note-event segmentation. Both platforms must pass the acceptance criteria before the MVP is complete.

## MVP Goal

A musician can open the app, start listening, play one sustained note, and see a stable note name and cents deviation quickly enough to adjust their pitch. They can stop and start another practice session without restarting the app.

The MVP supports free tuning, practicing a selected note, and following a simple monophonic score. It automatically groups detected pitch frames into played-note events and uses the exercise or score target to distinguish an in-tune wrong note from the intended note.

### Supported Input and Tuning

| Decision | MVP scope |
|----------|-----------|
| Input | One sustained note at a time through the built-in microphone, in a quiet room |
| Pitch range | E2–C6, approximately 82.4–1046.5 Hz; validation must cover the full range |
| Tuning system | 12-tone equal temperament, concert pitch, A4 = 440 Hz |
| Target note | Nearest chromatic note in free tuning; selected note in exercises; current score note in score following |
| Platforms | Android with Jetpack Compose and iOS with SwiftUI; all three practice modes on both |
| Note spelling | Sharps with octave numbers, such as C#4; no transposing-instrument display |
| In-tune tolerance | Absolute deviation ≤ 10 cents |
| Data handling | On-device processing; audio is neither saved nor uploaded |

The range is an initial product boundary, not a claim that every instrument or playing technique is supported. Chords, noisy ensembles, and percussive attacks are outside the MVP's accuracy requirements.

### Practice Modes

- **Free tuning:** display cents relative to the nearest chromatic note.
- **Selected-note exercise:** choose any note and octave within E2–C6. Display both the target and detected note, and measure cents against the selected target even when another note is closer. Mark success after holding the target within ±10 cents for 500 ms of continuous valid input; silence, an invalid reading, or leaving tolerance resets the hold timer. After success, latch the result and require a new articulation before beginning another attempt on the same target. Continuing to sustain the note, drifting out of tolerance and back, or a brief detection dropout must not count as a new attempt. Rearm only when segmentation confirms a newly articulated note event; a pitch change alone is insufficient. The selected target remains unchanged until the user selects a new one.
- **Score following:** choose a bundled short monophonic exercise, view its staff notation, and see the current expected note highlighted. Follow at the player's pace: advance once when a confirmed played-note event matches the expected note and octave. Show intonation feedback separately; a correct note may advance even when outside the ±10-cent in-tune tolerance. After advancing, highlight the next expected score note while keeping the live cents meter, numeric deviation, and tuning label tied to the sounding event's original target. Do not relabel the sustained note as wrong merely because the cursor moved. Keep this feedback through the final sounding event even after the score is marked complete. A wrong note, silence, or an uncertain detection must not advance the cursor. Provide restart and manual previous/next controls for recovery, and show completion after the final note is matched.

For the MVP, scores are bundled ordered note sequences within E2–C6, with notation durations for display. Include at least a scale, an exercise with repeated notes, and a short melody. Rhythm grading, rests, ties, repeats, multiple voices, arbitrary score import, and automatic recovery after skipped notes are deferred. Repeated adjacent notes require a new articulated note event; one sustained note cannot consume multiple score positions.

### Automatic Note-Event Segmentation

Convert valid pitch frames into events with an onset timestamp, end timestamp, representative note and octave, and median signed cents deviation relative to the applicable target. Keep an active event separate from completed history.

- Confirm a new pitch only after it remains stable for at least 100 ms; short transients must not create events.
- End an event on a confirmed pitch change or after at least 150 ms without reliable pitch. Shorter dropouts do not split an event.
- Detect a clear rearticulation of the same pitch as a new event, including an amplitude onset without an intervening silence. A sustained note or vibrato must not repeatedly create new events.
- Use note-boundary hysteresis to avoid splitting events when pitch fluctuates around a semitone boundary. Calibrate the hysteresis and onset thresholds against deterministic fixtures.
- Notify score following once per confirmed event. Snapshot the expected score position and target when each event begins, including for wrong-note events, and retain them for live feedback and history. Later frames cannot advance the cursor again or change the event's intonation reference. Keep the next expected score position separate from the sounding event's feedback target.
- On stop, interruption, or a mode/target change, finalize any confirmed active event. Changing mode or target starts a fresh exercise attempt and clears its history.

### User Flow and Feedback

1. Open the app in an idle state, choose **Free tuning**, **Selected note**, or **Follow score**, and select a target or bundled score when applicable. Tap **Start listening**.
2. Request microphone permission when needed. If denied, explain how to enable it and allow retry without crashing.
3. While listening, show the detected note and octave, frequency in Hz, signed cents deviation, and a centered cents meter. In exercises, also show the target and hold progress; in score following, show the score cursor and current expected note. If the deviation exceeds the meter range, pin the indicator to the edge while preserving the full numeric value.
4. Use absolute cents deviation for consistent feedback: green for ≤ 10 cents, amber for > 10 and ≤ 20 cents, and red for > 20 cents. Also show **In tune**, **Flat**, or **Sharp** so color is not the only signal.
5. In exercise and score modes, label a mismatched note or octave **Wrong note** instead of suggesting it is in tune with the target. When there is silence or no reliable pitch, show **No clear note** and clear the live reading. Never show silence as an in-tune note or leave a stale reading visible indefinitely.
6. Show **Recent notes** containing the latest 16 completed note events, with note name, duration, and median signed cents deviation. Silence and unconfirmed transients never enter history.
7. **Stop listening** releases the microphone and clears the live reading. Keep the history visible until the next session starts; starting a new session clears it. Every start of listening resets the selected score to its first note, clears completion and consumed-event state, and resets any selected-note attempt. This also applies when restarting after backgrounding or interruption; the selected score or exercise target remains selected.

Listening stops when the app enters the background or audio is interrupted. Returning to the app requires an explicit start. Capture failures show an actionable error and a retry control; the UI only reports listening after capture starts successfully.

### Out of Scope

- Chord or polyphonic detection, accompaniment separation, and ensemble use
- Score import (including PDF, images, and MusicXML), score editing, and advanced score structures
- Alternate tuning references, temperaments, or instrument transposition
- Session scores, long-term analytics, accounts, and cloud sync
- Audio recording, playback, and export
- Tempo detection, rhythm feedback, and spectrum visualization
- Background listening and Bluetooth microphone support

## MVP Acceptance Criteria

These are release targets to validate, not measured performance claims.

| Area | Required evidence |
|------|-------------------|
| Build | Android debug build, iOS simulator build, and core unit tests for both implementations pass from documented development environments |
| Note mapping | 440 Hz maps to A4 at 0 cents; 880 Hz maps to A5 at 0 cents; tones shifted by ±25 cents map to the expected signed deviation |
| Detector accuracy | For steady synthetic sine and harmonic-rich fixtures across E2–C6, after the first 250 ms, at least 95% of analysis windows produce the correct note within 5 cents of the known pitch, with no octave errors |
| Invalid input | Digital silence and fixed noise-only fixtures produce no accepted pitch readings and do not enter history |
| Device response | On each documented reference device (an Android phone and an iPhone), a stable reading appears within 250 ms of a sustained test-tone onset; the live reading clears within 500 ms after the tone stops |
| Real instrument | On each platform, document a physical-device session with at least one monophonic instrument or voice, including low, middle, and high notes within its supported range, note changes, and silence |
| Lifecycle | Ten consecutive start/stop cycles work; permission denial, backgrounding, and audio interruption leave the app recoverable and release the microphone |
| UI consistency | Meter, numeric deviation, text, and colors use the same tuning rules; history remains bounded and resets on a new session |
| Selected-note exercise | On both platforms, selecting A4 makes 440 Hz in tune and 880 Hz a wrong-octave note at +1200 cents; success requires a continuous 500 ms in-tune hold, and invalid or out-of-tolerance input resets the timer before success. After success, a sustained note, pitch drift, or brief dropout cannot start another attempt; a new articulation rearms it and requires a fresh 500 ms hold |
| Segmentation | Shared labeled fixtures cover sustained notes, pitch changes, repeated articulated notes with and without silence, vibrato, short transients, and brief dropouts; both implementations produce the expected event count and notes, with onset/end timestamps within 150 ms of labeled boundaries |
| Score following | Both platforms render all bundled exercises and follow a scripted sequence containing correct, wrong, repeated, and out-of-tune notes; wrong notes do not advance, correct events advance exactly once, a held note cannot consume repeated positions, and restart/manual navigation/completion work. After matching A4 in an A4–B4 sequence, sustained A4 remains measured against A4 while B4 is highlighted. Stopping and restarting listening returns to the first note with no stale consumed-event state, including after interruption |
| Platform parity | Run the same fixtures and behavioral scenarios on Android and iOS; all three modes, event history, and lifecycle recovery meet the same requirements |
| Privacy | No raw audio files are created and no audio is transmitted |

Store deterministic audio fixtures with their sample rates and expected frequencies. Record each reference device, OS version, fixture definitions (including event boundaries and expected score transitions), and measurement procedure alongside validation results so the checks can be repeated.

## Planned Architecture

```text
Microphone → PCM capture → Overlapping analysis windows → Pitch detector
                                                           ↓
                                                     Note mapping + confidence gate
                                                           ↓
                                                   Note-event segmentation
                                                           ↓
UI ← Observable session state ← Exercise / score follower + Recent notes
```

- **Capture:** Android `AudioRecord` and an iOS `AVAudioEngine` input-node tap. Prefer mono PCM and pass the actual capture sample rate through the pipeline.
- **Analysis:** consume each window once on a background worker. Use a bounded buffer and discard outdated work if processing falls behind.
- **Detector:** a platform-independent YIN normalized-difference implementation accepts samples and sample rate and returns frequency plus confidence, or no reliable pitch. Kotlin and Swift use matching algorithms and shared fixtures. The legacy FFT implementation has been removed.
- **Windowing:** 2,048 analysis samples with a 512-sample hop. At 48 kHz capture, pairwise averaging reduces analysis to 24 kHz, giving an approximately 85 ms window and 21 ms hop. Other input rates retain their actual sample rate in calculations; 44.1 kHz is covered by fixtures.
- **Validity:** reject levels below −55 dBFS, confidence below 0.85, and notes outside E2–C6. Confirm event boundaries after 100 ms, retain events through dropouts shorter than 150 ms, and use a 65-cent boundary hysteresis. A 6 dB envelope dip/rise with at least a 3 dB hop rise and a 180 ms onset cooldown identifies clear rearticulations. These initial thresholds pass the synthetic fixtures; instrument-specific tuning remains part of device validation.
- **Practice model:** represent bundled scores as ordered notes with stable IDs, MIDI pitch, and notation duration. Keep selected targets, score position, hold timers, and confirmed event IDs independent of UI rendering. Store the sounding event's feedback target separately from the next expected score position. Selected-note success stays latched until a new articulation is confirmed. Manual navigation must not reuse an already-consumed event. Every listening start resets score position and attempt state.
- **Segmentation:** maintain an active note event, confirm boundaries using pitch stability and amplitude onsets, and feed event confirmations to score following and completed events to bounded history. Use the same fixtures and target-reference rules on both platforms.
- **Presentation:** apply modest smoothing to valid readings without delaying note changes excessively. Keep session state and processing outside the UI; publish observable state through an Android ViewModel and an iOS observable state model.
- **Lifecycle:** one session owns capture and processing. Starting, stopping, errors, and interruptions must cancel work and release resources consistently.

### Note Mapping

For a valid detected frequency `f`:

```text
fractionalMidi = 69 + 12 * log2(f / 440)
nearestMidi    = round(fractionalMidi)
targetMidi     = nearestMidi (free tuning), selectedMidi (exercise), or activeEvent.targetMidi (following)
targetHz       = 440 * 2^((targetMidi - 69) / 12)
cents          = 1200 * log2(f / targetHz)
```

Negative cents means flat; positive cents means sharp. Silence and unreliable detections are represented explicitly as no pitch, rather than a sentinel cents value that could be mistaken for an accurate note.

## Implementation Milestones

Milestones 1–5 are implemented and covered by the software checks below. Milestone 6 remains open for physical-device acceptance.

1. **Runnable platform shells:** fix Android build issues and the iOS app entry point/dependencies; implement permissions, explicit session states, and repeatable microphone start/stop on both platforms.
2. **Verified pitch engines:** correct note mapping, implement detector interfaces, and pass shared deterministic accuracy and invalid-input fixtures independently of the microphones.
3. **Live tuning on both platforms:** connect capture, windowing, confidence gating, and observable UI state; implement the cents meter and lifecycle recovery.
4. **Note events and selected-note exercises:** implement segmentation, completed-event history, target selection, and hold-to-succeed feedback; validate repeated notes, vibrato, and wrong-octave input.
5. **Score following:** add bundled exercises, staff notation, cursor tracking, event-to-note matching, manual recovery, and completion on Android and iOS.
6. **MVP release validation:** pass all acceptance criteria on an Android reference phone and an iPhone; document results and remaining limitations. Neither platform is deferred beyond the MVP.

Future work can add score import, advanced score alignment, and long-term practice statistics. Polyphony, recording/export, and rhythm analysis require separate designs and validation.

## Getting Started

### Prerequisites

- Android Studio (for Android)
- Xcode 27 (verified build toolchain; the iOS deployment target is 15.0)
- Android SDK with API level 37 (the Android project's compile SDK)
- macOS (required for iOS builds)

### Build & Run

#### On Android:
```bash
cd Android
# On macOS, use the Java runtime bundled with Android Studio:
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
# or from Android Studio: Build → Make Project
```

The debug APK is generated at `Android/app/build/outputs/apk/debug/app-debug.apk`.
If your Android Studio installation is elsewhere, adjust `JAVA_HOME` to its JDK directory.

Then run on a physical device or emulator. **Note:** You must grant microphone permission when prompted.

#### On iOS (macOS only):
```bash
cd IOS
open "Intonation Trainer.xcodeproj"
# Use a physical device for microphone and latency validation
```

The iOS shell uses only SwiftUI and AVFoundation; CocoaPods is not required. For a command-line simulator build from the repository root:

```bash
xcodebuild -project "IOS/Intonation Trainer.xcodeproj" \
  -scheme "Intonation Trainer" -sdk iphonesimulator \
  -configuration Debug -derivedDataPath /tmp/intonation-shell-ios \
  CODE_SIGNING_ALLOWED=NO build
```

To run on an iPhone, select your signing team in Xcode and choose the connected device.

### Automated Validation

From the repository root, run `bash scripts/check.sh` to build both apps and run both core test suites. It requires macOS, the documented Android SDK/JDK, and Xcode. No microphone access is needed for core tests.

Run Android UI tests on a booted emulator with `./gradlew connectedDebugAndroidTest` from `Android/`. Run iOS UI tests through the shared **Intonation Trainer** scheme's Test action in Xcode, or:

```bash
xcodebuild -project "IOS/Intonation Trainer.xcodeproj" \
  -scheme "Intonation Trainer" \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro' \
  -derivedDataPath /tmp/intonation-mvp-ios CODE_SIGNING_ALLOWED=NO test
```

Choose a simulator name installed on your machine. Both UI suites exercise mode selection, target selection, and score navigation without requesting microphone permission.

The core tests cover pitch mapping, 114 generated-tone cases, silence/noise rejection, event boundaries, repeated articulation, vibrato, exercise holds, wrong octaves, score progression, manual recovery, completion, and bounded history. Android also tests capture-session lifecycle with a fake source. Fixture definitions are in [fixtures/README.md](fixtures/README.md).

### Physical-Device Validation

On each physical platform, verify:

1. Deny microphone permission; confirm the error, Settings link, and retry path.
2. Grant permission and start listening; play or speak and check the input-level meter.
3. Stop and restart ten times; confirm the microphone indicator clears on stop and capture resumes each time.
4. Background the app or interrupt audio; confirm listening stops and requires an explicit restart.
5. Return from Settings or an interruption and retry; confirm no stale callback restarts a stopped session.

Also complete the pitch-latency and instrument checklist in [docs/VALIDATION.md](docs/VALIDATION.md). Synthetic tests and simulator navigation do not establish acoustic performance on phones.

## Project Structure

```text
Android/app/src/main/java/com/example/intonationtrainer/
├── MainActivity.kt           # Permissions, audio focus, Android lifecycle
├── TrainerViewModel.kt       # Observable UI state and practice-session ownership
├── core/
│   ├── audio/AudioRecorderSource.kt   # PCM capture and background analysis
│   ├── session/MicrophoneSession.kt  # Capture state and stale-callback protection
│   └── practice/
│       ├── PitchPipeline.kt  # Streaming windows, YIN, RMS, amplitude onsets
│       └── PracticeEngine.kt # Notes, scores, segmentation, holds, score following
└── ui/
    ├── screens/pitchvisualizer/TrainerScreen.kt # Modes, meter, staff, history
    └── theme/                # Compose colors and typography

IOS/
├── Intonation Trainer.xcodeproj/ # App, UI-test target, and shared scheme
├── Package.swift             # Runs platform-independent core tests on macOS
├── CoreTests/                # Swift tests against shared fixtures
├── UITests/                  # SwiftUI navigation checks and screenshots
└── Intonation_Trainer/
    ├── Intonation_TrainerApp.swift
    ├── ContentView.swift     # Modes, cents meter, staff notation, history
    ├── MicrophoneSession.swift # AVAudioEngine, bounded worker, UI state
    ├── Core/
    │   ├── PitchPipeline.swift
    │   └── PracticeEngine.swift
    └── Assets.xcassets/

fixtures/                     # Shared tone recipes, labeled events, scores
scripts/check.sh              # Builds and core tests for both platforms
docs/VALIDATION.md            # Evidence, reproducible commands, device checklist
```

Legacy UIKit delegates and plist files remain excluded from the iOS target. The target generates its Info.plist with the microphone usage description in the project settings. The active app has no CocoaPods dependencies.

## License

MIT License — feel free to use and modify.
