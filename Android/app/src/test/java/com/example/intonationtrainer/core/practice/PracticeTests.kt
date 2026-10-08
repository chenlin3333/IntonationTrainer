package com.example.intonationtrainer.core.practice

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

class PracticeTests {
    private fun csv(name:String)=File("../../fixtures/$name.csv").readLines().drop(1).map { it.split(',') }
    private fun frame(t:Int,midi:Int?=69,cents:Double=0.0,onset:Boolean=false)=AnalysisFrame(t.toDouble(),midi?.let { Notes.hz(it)*2.0.pow(cents/1200) },if(midi==null) 0.0 else 0.99,if(midi==null) -90.0 else -20.0,onset)
    private fun feed(e:PracticeEngine,start:Int,end:Int,midi:Int?=69,cents:Double=0.0,onset:Boolean=false) { for(t in start until end step 20) e.accept(frame(t,midi,cents,onset && t==start)) }
    @Test fun mapping() { for(m in 40..84) for(c in listOf(-25.0,0.0,25.0)) { val hz=Notes.hz(m)*2.0.pow(c/1200); assertEquals(m,Notes.midi(hz)); assertEquals(c,Notes.cents(hz,m),1e-7) }; assertEquals("A4",Notes.name(69)) }
    @Test fun toneFixturesMeetAccuracyAtBothSampleRates() {
        for(row in csv("tones")) {
            val midi=row[0].toInt(); val hz=Notes.hz(midi)*2.0.pow(row[1].toDouble()/1200); val rate=row[3].toDouble()
            val frames=mutableListOf<AnalysisFrame>(); val pipe=PitchPipeline(rate) { frames+=it }
            val samples=FloatArray((rate*0.5).toInt()) { i -> val p=2*PI*hz*i/rate; (if(row[2]=="sine") 0.4*sin(p) else 0.2*sin(p)+0.3*sin(2*p)+0.15*sin(3*p)).toFloat() }
            samples.asList().chunked(777).forEach { pipe.accept(it.toFloatArray()) }
            val stable=frames.filter { it.timeMs>=250 }; assertTrue(stable.isNotEmpty())
            val good=stable.count { it.frequency!=null && Notes.midi(it.frequency)==midi && abs(1200*log2(it.frequency/hz))<=5 }
            assertTrue("$row: $good/${stable.size}",good.toDouble()/stable.size>=0.95)
            assertTrue("octave error $row",stable.all { it.frequency==null || abs(Notes.midi(it.frequency)-midi)<12 })
        }
    }
    @Test fun silenceAndDeterministicNoiseAreRejected() {
        var seed=12345L
        for(noise in listOf(false,true)) {
            val frames=mutableListOf<AnalysisFrame>(); val p=PitchPipeline(48000.0) { frames+=it }
            p.accept(FloatArray(24000) { seed=(1664525*seed+1013904223) and 0xffffffffL; if(noise) ((seed.toDouble()/4294967296-0.5)*0.5).toFloat() else 0f })
            assertTrue(frames.all { it.frequency==null })
        }
    }
    @Test fun sharedSegmentationFixtures() {
        val expected=csv("expected-events").associateBy { it[0] }
        for((name,rows) in csv("events").groupBy { it[0] }) {
            val e=PracticeEngine()
            rows.forEach { e.accept(AnalysisFrame(it[1].toDouble(),it[2].toDouble().takeIf { f->f>0 },it[3].toDouble(),it[4].toDouble(),it[5]=="1")) }
            val exp=expected.getValue(name); val notes=exp[1].split(';').filter { it.isNotEmpty() }.map { it.toInt() }
            assertEquals(name,notes,e.state.history.map { it.midi })
            for(i in notes.indices) { assertEquals(name,exp[2].split(';')[i].toDouble(),e.state.history[i].startMs,150.0); assertEquals(name,exp[3].split(';')[i].toDouble(),e.state.history[i].endMs,150.0) }
        }
    }
    @Test fun selectedSuccessRequiresNewArticulation() {
        val e=PracticeEngine(); e.configure(mode=PracticeMode.SELECTED)
        feed(e,0,800); assertTrue(e.state.success); assertEquals(1,e.state.successes)
        feed(e,800,1000,71); feed(e,1000,1800); assertEquals(1,e.state.successes)
        feed(e,1800,1860,null); feed(e,1860,2500); assertEquals(1,e.state.successes)
        feed(e,2500,3300,onset=true); assertEquals(2,e.state.successes)
    }
    @Test fun invalidAndOffPitchResetHoldAndOctaveIsWrong() {
        val e=PracticeEngine(); e.configure(mode=PracticeMode.SELECTED)
        feed(e,0,400); feed(e,400,420,null); feed(e,420,800); assertFalse(e.state.success)
        feed(e,800,900,cents=25.0); feed(e,900,1300); assertFalse(e.state.success)
        feed(e,1300,1500,81); assertEquals("Wrong note",e.state.live!!.label); assertEquals(1200.0,e.state.live!!.cents,0.01)
    }
    @Test fun scoreConsumesEventsOnceAndKeepsSoundingTarget() {
        val e=PracticeEngine(); e.configure(mode=PracticeMode.SCORE,scoreIndex=1)
        feed(e,0,400,67); assertEquals(0,e.cursor)
        feed(e,400,1200,69,cents=25.0); assertEquals(1,e.cursor); assertEquals(69,e.live!!.target)
        feed(e,1200,1600,69,onset=true); assertEquals(2,e.cursor); assertEquals(69,e.live!!.target); assertEquals("In tune",e.live!!.label)
        e.navigate(0); feed(e,1600,2000); assertEquals(0,e.cursor)
        e.stop(); e.reset(); assertEquals(0,e.cursor); assertNull(e.live); assertTrue(e.state.history.isEmpty())
    }
    @Test fun completionAndHistoryBounds() {
        val e=PracticeEngine(); e.configure(mode=PracticeMode.SCORE)
        e.score.notes.forEachIndexed { i,n -> feed(e,i*300,(i+1)*300,n.midi) }
        assertTrue(e.state.complete); assertEquals(72,e.live!!.target)
        e.configure(); for(i in 0..20) feed(e,i*300,(i+1)*300,if(i%2==0) 69 else 71)
        e.stop(); assertEquals(16,e.state.history.size)
    }
    @Test fun sharedScores() { for((i,row) in csv("scores").withIndex()) { assertEquals(row[0],Scores.all[i].id); assertEquals(row[1],Scores.all[i].notes.joinToString(";") { it.midi.toString() }); assertEquals(row[2],Scores.all[i].notes.joinToString(";") { it.beats.toString() }) } }
    @Test fun audioEnvelopeDetectsRepeatedArticulationWithoutSilence() {
        val frames=mutableListOf<AnalysisFrame>(); val p=PitchPipeline(48000.0) { frames+=it }
        p.accept(FloatArray(48000) { i -> val t=i/48000.0; val amplitude=if(t>=0.35 && t<0.42) 0.04 else 0.4; (amplitude*sin(2*PI*440*t)).toFloat() })
        assertTrue(frames.any { it.onset && it.timeMs in 420.0..480.0 })
        val e=PracticeEngine(); frames.forEach(e::accept); e.stop(); assertEquals(2,e.state.history.size)
    }
    @Test fun waveformToHistoryAndSilenceLatency() {
        val frames=mutableListOf<AnalysisFrame>(); val pipeline=PitchPipeline(48000.0) { frames+=it }
        val e=PracticeEngine()
        pipeline.accept(FloatArray(120000) { i ->
            val t=i/48000.0
            val hz=when { t<0.65 -> 440.0; t<0.85 -> 0.0; t<1.5 -> 440.0; t<2.15 -> Notes.hz(71); else -> 0.0 }
            (0.4*sin(2*PI*hz*t)).toFloat()
        })
        frames.forEach(e::accept)
        assertEquals(listOf(69,69,71),e.state.history.map { it.midi })
        assertNull(e.live)
        assertTrue(frames.first { it.frequency!=null }.timeMs<=250)
        assertTrue(frames.any { it.timeMs in 2150.0..2650.0 && it.frequency==null })
        assertTrue(e.state.history.all { abs(it.medianCents)<5 })
    }
    @Test fun fullAudioVibratoDoesNotCreateRepeatedNotes() {
        val e=PracticeEngine(); val pipeline=PitchPipeline(48000.0,e::accept)
        var phase=0.0
        pipeline.accept(FloatArray(48000) { i -> val hz=440*2.0.pow(25*sin(2*PI*5*i/48000)/1200); phase+=2*PI*hz/48000; (0.4*sin(phase)).toFloat() })
        e.stop(); assertEquals(1,e.state.history.size)
    }
    @Test fun confidenceAndBoundaryHysteresis() {
        val e=PracticeEngine(); feed(e,0,300)
        for(t in 300 until 700 step 20) e.accept(frame(t,cents=if(t%40==0) 49.0 else 55.0))
        e.stop(); assertEquals(1,e.state.history.size)
        e.reset(); for(t in 0..300 step 20) e.accept(frame(t).copy(confidence=0.2))
        assertNull(e.live); assertTrue(e.state.history.isEmpty())
    }

}
