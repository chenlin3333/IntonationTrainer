package com.example.intonationtrainer.core.session

import org.junit.Assert.*
import org.junit.Test

class MicrophoneSessionTest {
    private class FakeCapture : MicrophoneSession.Capture {
        data class Call(val started: () -> Unit, val level: (Float) -> Unit, val error: (String) -> Unit)
        val calls = mutableListOf<Call>()
        var stops = 0
        override fun start(onStarted: () -> Unit, onLevel: (Float) -> Unit, onError: (String) -> Unit) {
            calls += Call(onStarted, onLevel, onError)
        }
        override fun stop() { stops++ }
    }

    @Test fun startsOnlyAfterCaptureSucceedsAndCanRepeatTenTimes() {
        val capture = FakeCapture()
        val session = MicrophoneSession(capture) {}
        repeat(10) {
            session.start()
            assertEquals(MicrophoneSession.Phase.STARTING, session.state.phase)
            capture.calls.last().started()
            assertEquals(MicrophoneSession.Phase.LISTENING, session.state.phase)
            capture.calls.last().level(-25f)
            assertEquals(-25f, session.state.levelDb)
            session.stop()
            assertEquals(MicrophoneSession.Phase.IDLE, session.state.phase)
            assertEquals(-90f, session.state.levelDb)
        }
        assertEquals(10, capture.calls.size)
        assertEquals(10, capture.stops)
    }

    @Test fun stoppedSessionCannotPublishLateCallbacksIntoNewSession() {
        val capture = FakeCapture()
        val session = MicrophoneSession(capture) {}
        session.start()
        val old = capture.calls.last()
        session.stop()
        session.start()
        old.started(); old.level(-1f); old.error("old failure")
        assertEquals(MicrophoneSession.Phase.STARTING, session.state.phase)
        capture.calls.last().started()
        assertEquals(MicrophoneSession.Phase.LISTENING, session.state.phase)
    }

    @Test fun captureFailureReleasesAndAllowsRetry() {
        val capture = FakeCapture()
        val session = MicrophoneSession(capture) {}
        session.start()
        capture.calls.last().error("Microphone busy")
        assertEquals(MicrophoneSession.Phase.ERROR, session.state.phase)
        assertEquals("Microphone busy", session.state.message)
        assertEquals(1, capture.stops)
        session.start()
        capture.calls.last().started()
        assertEquals(MicrophoneSession.Phase.LISTENING, session.state.phase)
    }

    @Test fun duplicateStartsDoNotOpenAnotherRecorder() {
        val capture = FakeCapture()
        val session = MicrophoneSession(capture) {}
        session.start(); session.start()
        capture.calls.last().started()
        session.start()
        assertEquals(1, capture.calls.size)
    }

    @Test fun interruptionDuringStartupDiscardsPendingSuccess() {
        val capture = FakeCapture()
        val session = MicrophoneSession(capture) {}
        session.start()
        session.stop("Interrupted")
        capture.calls.last().started()
        assertEquals(MicrophoneSession.Phase.IDLE, session.state.phase)
        assertEquals("Interrupted", session.state.message)
    }
}
