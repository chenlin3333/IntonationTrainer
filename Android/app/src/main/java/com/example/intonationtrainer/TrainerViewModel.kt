package com.example.intonationtrainer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.intonationtrainer.core.audio.AudioRecorderSource
import com.example.intonationtrainer.core.practice.*
import com.example.intonationtrainer.core.session.MicrophoneSession

class TrainerViewModel : ViewModel() {
    private val engine=PracticeEngine()
    var practice by mutableStateOf(engine.state); private set
    var microphone by mutableStateOf(MicrophoneSession.State()); private set
    private val capture=AudioRecorderSource { frame -> engine.accept(frame); practice=engine.state }
    private val session=MicrophoneSession(capture) {
        microphone=it
        if(it.phase==MicrophoneSession.Phase.ERROR) { engine.stop(); practice=engine.state }
    }
    fun start() { if(microphone.phase==MicrophoneSession.Phase.LISTENING || microphone.phase==MicrophoneSession.Phase.STARTING) return; engine.reset(); practice=engine.state; session.start() }
    fun stop(message:String="Ready to listen") { session.stop(message); engine.stop(); practice=engine.state }
    fun fail(message:String) { session.fail(message) }
    fun configure(mode:PracticeMode=engine.mode,selected:Int=engine.selected,scoreIndex:Int=engine.scoreIndex) { engine.configure(mode,selected,scoreIndex); practice=engine.state }
    fun navigate(index:Int) { engine.navigate(index); practice=engine.state }
    override fun onCleared() { capture.close() }
}
