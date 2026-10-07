package com.example.intonationtrainer.core.session

/** Main-thread session state. Generation IDs reject callbacks from stopped sessions. */
class MicrophoneSession(
    private val capture: Capture,
    private val onChange: (State) -> Unit
) {
    interface Capture {
        fun start(onStarted: () -> Unit, onLevel: (Float) -> Unit, onError: (String) -> Unit)
        fun stop()
    }

    enum class Phase { IDLE, STARTING, LISTENING, ERROR }
    data class State(val phase: Phase = Phase.IDLE, val levelDb: Float = -90f, val message: String = "Ready to listen")
    var state = State()
        private set
    private var generation = 0

    private fun publish(value: State) { state = value; onChange(value) }

    fun start() {
        if (state.phase == Phase.STARTING || state.phase == Phase.LISTENING) return
        val token = ++generation
        publish(State(Phase.STARTING, message = "Starting microphone…"))
        capture.start(
            onStarted = { if (token == generation) publish(State(Phase.LISTENING, message = "Listening")) },
            onLevel = { if (token == generation && state.phase == Phase.LISTENING) publish(state.copy(levelDb = it)) },
            onError = { if (token == generation) fail(it) }
        )
    }

    fun stop(message: String = "Ready to listen") {
        generation++
        capture.stop()
        publish(State(message = message))
    }

    fun fail(message: String) {
        generation++
        capture.stop()
        publish(State(Phase.ERROR, message = message))
    }
}
