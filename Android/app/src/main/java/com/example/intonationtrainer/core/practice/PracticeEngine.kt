package com.example.intonationtrainer.core.practice

import kotlin.math.*

enum class PracticeMode(val title: String) { TUNER("Free tuning"), SELECTED("Selected note"), SCORE("Follow score") }
object Notes {
    fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    fun midi(hz: Double) = (69 + 12 * log2(hz / 440)).roundToInt()
    fun cents(hz: Double, midi: Int) = 1200 * log2(hz / hz(midi))
    fun name(midi: Int) = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[midi % 12] + (midi / 12 - 1)
    fun staffStep(midi: Int) = (midi / 12 - 1) * 7 + intArrayOf(0,0,1,1,2,3,3,4,4,5,5,6)[midi % 12]
}
data class ScoreNote(val id: Int, val midi: Int, val beats: Int = 1)
data class PracticeScore(val id: String, val title: String, val notes: List<ScoreNote>)
object Scores {
    val all = listOf(
        PracticeScore("scale", "C major scale", listOf(60,62,64,65,67,69,71,72).mapIndexed { i, n -> ScoreNote(i,n,if(i==7) 2 else 1) }),
        PracticeScore("repeated", "Repeated notes", listOf(69,69,71,71,72,72,71,69).mapIndexed { i,n -> ScoreNote(i,n) }),
        PracticeScore("melody", "Little melody", listOf(64,62,60,62,64,64,64,62,62,62,64,67,67).mapIndexed { i,n -> ScoreNote(i,n,if(i==6||i==9||i==12) 2 else 1) })
    )
}
data class AnalysisFrame(val timeMs: Double, val frequency: Double?, val confidence: Double, val levelDb: Double, val onset: Boolean = false)
data class NoteEvent(val id: Long, val midi: Int, val target: Int, val startMs: Double, val endMs: Double, val medianCents: Double) {
    val durationMs get() = max(0.0, endMs-startMs)
}
data class LivePitch(val frequency: Double, val midi: Int, val target: Int, val cents: Double) {
    val label get() = if(midi != target) "Wrong note" else if(abs(cents)<=10) "In tune" else if(cents<0) "Flat" else "Sharp"
}
data class PracticeState(
    val mode: PracticeMode, val selected: Int, val scoreIndex: Int, val cursor: Int,
    val complete: Boolean, val live: LivePitch?, val history: List<NoteEvent>,
    val holdProgress: Double, val success: Boolean, val successes: Int
)

/** Deterministic, main-thread model. All times are monotonic audio timestamps in milliseconds. */
class PracticeEngine {
    var mode = PracticeMode.TUNER; private set
    var selected = 69; private set
    var scoreIndex = 0; private set
    var cursor = 0; private set
    var live: LivePitch? = null; private set
    private val history = ArrayDeque<NoteEvent>()
    private var active: Active? = null
    private var pending: Pending? = null
    private var nextId = 0L
    private var lastValid = Double.NEGATIVE_INFINITY
    private var lastTime = Double.NEGATIVE_INFINITY
    private var lastAttack = Double.NEGATIVE_INFINITY
    private var holdStart: Double? = null
    private var progress = 0.0
    private var success = false
    private var successes = 0
    private var smoothedCents: Double? = null
    private var smoothTarget: Int? = null
    private var smoothMidi: Int? = null
    private class Active(val id: Long, val midi: Int, val target: Int, val start: Double) {
        // Bounded histogram: tenth-cent precision over the supported +/- 5,000-cent span.
        val bins = sortedMapOf<Int,Int>()
        var count = 0
        fun add(f: AnalysisFrame) { val bin = (Notes.cents(f.frequency!!,target)*10).roundToInt().coerceIn(-50000,50000); bins[bin]=(bins[bin]?:0)+1; count++ }
        fun median(): Double {
            var seen=0; var lo: Int?=null
            for((bin,n) in bins) { seen+=n; if(lo==null && seen>(count-1)/2) lo=bin; if(seen>count/2) return ((lo?:bin)+bin)/20.0 }
            return 0.0
        }
    }
    private data class Pending(val midi:Int, val target:Int, val start:Double, val articulated:Boolean, val frames:MutableList<AnalysisFrame>)
    val score get() = Scores.all[scoreIndex]
    val state get() = PracticeState(mode,selected,scoreIndex,cursor,cursor>=score.notes.size,live,history.toList(),progress,success,successes)
    fun configure(mode: PracticeMode = this.mode, selected: Int = this.selected, scoreIndex: Int = this.scoreIndex) {
        stop(); this.mode=mode; this.selected=selected.coerceIn(40,84); this.scoreIndex=scoreIndex.coerceIn(Scores.all.indices); reset()
    }
    fun reset() {
        active=null; pending=null; history.clear(); cursor=0; live=null; holdStart=null; progress=0.0; success=false; successes=0
        lastValid=Double.NEGATIVE_INFINITY; lastTime=Double.NEGATIVE_INFINITY; lastAttack=Double.NEGATIVE_INFINITY; clearSmoothing()
    }
    fun navigate(index: Int) {
        // Preserve the sounding event and its target. It has already been consumed.
        cursor=index.coerceIn(0,score.notes.size); pending=null
    }
    fun stop() { finish(lastValid); pending=null; live=null; holdStart=null; progress=0.0; clearSmoothing() }
    private fun clearSmoothing() { smoothedCents=null; smoothTarget=null; smoothMidi=null }
    private fun target(midi:Int) = when(mode) { PracticeMode.TUNER -> midi; PracticeMode.SELECTED -> selected; PracticeMode.SCORE -> score.notes.getOrNull(cursor)?.midi ?: score.notes.last().midi }
    private fun finish(end:Double) {
        active?.let { if(history.size==16) history.removeFirst(); history.addLast(NoteEvent(it.id,it.midi,it.target,it.start,max(it.start,end),it.median())) }
        active=null
    }
    fun accept(frame: AnalysisFrame) {
        val t=frame.timeMs
        if(!t.isFinite() || t<=lastTime) return
        if(t-lastTime>80) { holdStart=null; progress=0.0; pending=null }
        if(t-lastValid>=150) finish(lastValid)
        lastTime=t
        val f=frame.frequency
        if(f==null || !f.isFinite() || f<=0 || frame.confidence<0.85 || frame.levelDb < -55 || Notes.midi(f) !in 40..84) {
            live=null; clearSmoothing(); holdStart=null; progress=0.0; pending=null
            return
        }
        val midi=Notes.midi(f)
        val current=active
        val changed=current!=null && abs(Notes.cents(f,current.midi))>65
        val attack=frame.onset && t-lastAttack>=180
        if(attack) lastAttack=t
        if(current==null || changed || attack || pending!=null) {
            val candidate=pending
            if(candidate==null || candidate.midi!=midi || attack) {
                pending=Pending(midi,target(midi),t,current==null || attack,mutableListOf(frame))
            } else candidate.frames.add(frame)
            val p=pending!!
            if(current!=null && !changed && !p.articulated) pending=null
            else if(t-p.start>=100) {
                finish(p.start)
                val event=Active(++nextId,p.midi,p.target,p.start)
                p.frames.forEach(event::add); active=event; pending=null
                if(mode==PracticeMode.SELECTED && p.articulated && success) { success=false; holdStart=null; progress=0.0 }
                if(mode==PracticeMode.SCORE && cursor<score.notes.size && p.midi==score.notes[cursor].midi) cursor++
            }
        } else current?.add(frame)
        lastValid=t
        val reference=active?.target ?: pending?.target ?: target(midi)
        // Tuner uses the nearest note immediately; exercises preserve the event's original target.
        val displayTarget=if(mode==PracticeMode.TUNER) midi else reference
        val cents=Notes.cents(f,displayTarget)
        if(smoothTarget!=displayTarget || smoothMidi!=midi) smoothedCents=null
        val filtered=smoothedCents?.let { it+0.4*(cents-it) } ?: cents
        smoothedCents=filtered; smoothTarget=displayTarget; smoothMidi=midi
        live=LivePitch(f,midi,displayTarget,filtered)
        if(mode==PracticeMode.SELECTED && !success) {
            if(midi==selected && abs(Notes.cents(f,selected))<=10) {
                if(holdStart==null) holdStart=t
                progress=((t-holdStart!!)/500).coerceIn(0.0,1.0)
                if(progress>=1) { success=true; successes++ }
            } else { holdStart=null; progress=0.0 }
        }
    }
}
