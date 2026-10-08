import Foundation

enum PracticeMode: String, CaseIterable { case tuner = "Free tuning", selected = "Selected note", score = "Follow score" }
enum Notes {
    static func hz(_ midi: Int) -> Double { 440 * pow(2,Double(midi-69)/12) }
    static func midi(_ hz: Double) -> Int { Int((69+12*log2(hz/440)).rounded()) }
    static func cents(_ frequency: Double, _ midi: Int) -> Double { 1200*log2(frequency/hz(midi)) }
    static func name(_ midi: Int) -> String { ["C","C#","D","D#","E","F","F#","G","G#","A","A#","B"][midi%12] + String(midi/12-1) }
    static func staffStep(_ midi: Int) -> Int { (midi/12-1)*7 + [0,0,1,1,2,3,3,4,4,5,5,6][midi%12] }
}
struct ScoreNote: Identifiable { let id: Int; let midi: Int; let beats: Int }
struct PracticeScore { let id: String; let title: String; let notes: [ScoreNote] }
enum Scores {
    static let all = [
        PracticeScore(id:"scale",title:"C major scale",notes:[60,62,64,65,67,69,71,72].enumerated().map { ScoreNote(id:$0.offset,midi:$0.element,beats:$0.offset==7 ? 2:1) }),
        PracticeScore(id:"repeated",title:"Repeated notes",notes:[69,69,71,71,72,72,71,69].enumerated().map { ScoreNote(id:$0.offset,midi:$0.element,beats:1) }),
        PracticeScore(id:"melody",title:"Little melody",notes:[64,62,60,62,64,64,64,62,62,62,64,67,67].enumerated().map { ScoreNote(id:$0.offset,midi:$0.element,beats:[6,9,12].contains($0.offset) ? 2:1) })
    ]
}
struct AnalysisFrame { let timeMs: Double; let frequency: Double?; let confidence: Double; let levelDb: Double; var onset: Bool = false }
struct NoteEvent: Identifiable {
    let id: Int; let midi: Int; let target: Int; let startMs: Double; let endMs: Double; let medianCents: Double
    var durationMs: Double { max(0,endMs-startMs) }
}
struct LivePitch {
    let frequency: Double; let midi: Int; let target: Int; let cents: Double
    var label: String { midi != target ? "Wrong note" : abs(cents)<=10 ? "In tune" : cents<0 ? "Flat":"Sharp" }
}
struct PracticeState {
    let mode: PracticeMode; let selected: Int; let scoreIndex: Int; let cursor: Int; let complete: Bool
    let live: LivePitch?; let history: [NoteEvent]; let holdProgress: Double; let success: Bool; let successes: Int
}

/// Platform-independent state machine. Timestamps come from audio sample counts, never wall time.
final class PracticeEngine {
    private(set) var mode: PracticeMode = .tuner
    private(set) var selected = 69
    private(set) var scoreIndex = 0
    private(set) var cursor = 0
    private(set) var live: LivePitch?
    private var history: [NoteEvent] = []
    private var active: Active?
    private var pending: Pending?
    private var nextId = 0
    private var lastValid = -Double.infinity
    private var lastTime = -Double.infinity
    private var lastAttack = -Double.infinity
    private var holdStart: Double?
    private var progress = 0.0
    private var success = false
    private var successes = 0
    private var smoothedCents: Double?
    private var smoothTarget: Int?
    private var smoothMidi: Int?
    private final class Active {
        let id: Int; let midi: Int; let target: Int; let start: Double
        var bins: [Int:Int] = [:]; var count = 0
        init(_ id:Int,_ midi:Int,_ target:Int,_ start:Double) { self.id=id; self.midi=midi; self.target=target; self.start=start }
        func add(_ f:AnalysisFrame) {
            let bin=max(-50000,min(50000,Int((Notes.cents(f.frequency!,target)*10).rounded())))
            bins[bin,default:0]+=1; count+=1
        }
        func median() -> Double {
            var seen=0; var lo:Int?
            for bin in bins.keys.sorted() { seen+=bins[bin]!; if lo==nil && seen>(count-1)/2 { lo=bin }; if seen>count/2 { return Double((lo ?? bin)+bin)/20 } }
            return 0
        }
    }
    private struct Pending { let midi:Int; let target:Int; let start:Double; let articulated:Bool; var frames:[AnalysisFrame] }
    var score: PracticeScore { Scores.all[scoreIndex] }
    var state: PracticeState { PracticeState(mode:mode,selected:selected,scoreIndex:scoreIndex,cursor:cursor,complete:cursor>=score.notes.count,live:live,history:history,holdProgress:progress,success:success,successes:successes) }
    func configure(mode:PracticeMode?=nil,selected:Int?=nil,scoreIndex:Int?=nil) {
        stop(); self.mode=mode ?? self.mode; self.selected=max(40,min(84,selected ?? self.selected)); self.scoreIndex=max(0,min(Scores.all.count-1,scoreIndex ?? self.scoreIndex)); reset()
    }
    func reset() {
        active=nil; pending=nil; history=[]; cursor=0; live=nil; holdStart=nil; progress=0; success=false; successes=0
        lastValid = -.infinity; lastTime = -.infinity; lastAttack = -.infinity; clearSmoothing()
    }
    func navigate(_ index:Int) { cursor=max(0,min(score.notes.count,index)); pending=nil }
    func stop() { finish(lastValid); pending=nil; live=nil; holdStart=nil; progress=0; clearSmoothing() }
    private func clearSmoothing() { smoothedCents=nil; smoothTarget=nil; smoothMidi=nil }
    private func target(_ midi:Int) -> Int {
        switch mode { case .tuner: return midi; case .selected: return selected; case .score: return score.notes[min(cursor,score.notes.count-1)].midi }
    }
    private func finish(_ end:Double) {
        if let a=active {
            if history.count==16 { history.removeFirst() }
            history.append(NoteEvent(id:a.id,midi:a.midi,target:a.target,startMs:a.start,endMs:max(a.start,end),medianCents:a.median()))
        }
        active=nil
    }
    func accept(_ frame:AnalysisFrame) {
        let t=frame.timeMs
        guard t.isFinite,t>lastTime else { return }
        if t-lastTime>80 { holdStart=nil; progress=0; pending=nil }
        if t-lastValid>=150 { finish(lastValid) }
        lastTime=t
        guard let f=frame.frequency,f.isFinite,f>0,frame.confidence>=0.85,frame.levelDb >= -55,(40...84).contains(Notes.midi(f)) else {
            live=nil; clearSmoothing(); holdStart=nil; progress=0; pending=nil; return
        }
        let midi=Notes.midi(f)
        let current=active
        let changed=current.map { abs(Notes.cents(f,$0.midi))>65 } ?? false
        let attack=frame.onset && t-lastAttack>=180
        if attack { lastAttack=t }
        if current==nil || changed || attack || pending != nil {
            if pending==nil || pending!.midi != midi || attack {
                pending=Pending(midi:midi,target:target(midi),start:t,articulated:current==nil || attack,frames:[frame])
            } else { pending!.frames.append(frame) }
            let p=pending!
            if current != nil && !changed && !p.articulated { pending=nil }
            else if t-p.start>=100 {
                finish(p.start); nextId+=1
                let event=Active(nextId,p.midi,p.target,p.start)
                p.frames.forEach { event.add($0) }; active=event; pending=nil
                if mode == .selected && p.articulated && success { success=false; holdStart=nil; progress=0 }
                if mode == .score && cursor<score.notes.count && p.midi==score.notes[cursor].midi { cursor+=1 }
            }
        } else { current?.add(frame) }
        lastValid=t
        let reference=active?.target ?? pending?.target ?? target(midi)
        let displayTarget=mode == .tuner ? midi:reference
        let cents=Notes.cents(f,displayTarget)
        if smoothTarget != displayTarget || smoothMidi != midi { smoothedCents=nil }
        let filtered=smoothedCents.map { $0+0.4*(cents-$0) } ?? cents
        smoothedCents=filtered; smoothTarget=displayTarget; smoothMidi=midi
        live=LivePitch(frequency:f,midi:midi,target:displayTarget,cents:filtered)
        if mode == .selected && !success {
            if midi==selected && abs(Notes.cents(f,selected))<=10 {
                if holdStart==nil { holdStart=t }
                progress=max(0,min(1,(t-holdStart!)/500))
                if progress>=1 { success=true; successes+=1 }
            } else { holdStart=nil; progress=0 }
        }
    }
}
