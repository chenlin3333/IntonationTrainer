import XCTest
@testable import PracticeCore

final class PracticeTests: XCTestCase {
    func csv(_ name:String) throws -> [[String]] {
        let root=URL(fileURLWithPath:#filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        return try String(contentsOf:root.appendingPathComponent("fixtures/\(name).csv")).split(separator:"\n").dropFirst().map { $0.components(separatedBy:",") }
    }
    func feed(_ e:PracticeEngine,_ start:Int,_ end:Int,_ midi:Int?=69,cents:Double=0,onset:Bool=false) {
        for t in stride(from:start,to:end,by:20) { e.accept(AnalysisFrame(timeMs:Double(t),frequency:midi.map { Notes.hz($0)*pow(2,cents/1200) },confidence:midi==nil ? 0:0.99,levelDb:midi==nil ? -90:-20,onset:onset && t==start)) }
    }
    func testMapping() { for m in 40...84 { for c in [-25.0,0,25] { let hz=Notes.hz(m)*pow(2,c/1200); XCTAssertEqual(Notes.midi(hz),m); XCTAssertEqual(Notes.cents(hz,m),c,accuracy:1e-7) } }; XCTAssertEqual(Notes.name(69),"A4") }
    func testToneFixtures() throws {
        for row in try csv("tones") {
            let midi=Int(row[0])!, hz=Notes.hz(midi)*pow(2,Double(row[1])!/1200), rate=Double(row[3])!
            var frames:[AnalysisFrame]=[]; let pipe=PitchPipeline(rate:rate) { frames.append($0) }
            let samples:[Float]=(0..<Int(rate*0.5)).map { i in let p=2*Double.pi*hz*Double(i)/rate; return Float(row[2]=="sine" ? 0.4*sin(p):0.2*sin(p)+0.3*sin(2*p)+0.15*sin(3*p)) }
            for i in stride(from:0,to:samples.count,by:777) { pipe.accept(Array(samples[i..<min(i+777,samples.count)])) }
            let stable=frames.filter { $0.timeMs>=250 }; XCTAssertFalse(stable.isEmpty)
            let good=stable.filter { f in guard let h=f.frequency else { return false }; return Notes.midi(h)==midi && abs(1200*log2(h/hz))<=5 }.count
            XCTAssertGreaterThanOrEqual(Double(good)/Double(stable.count),0.95,"\(row)")
            XCTAssertTrue(stable.allSatisfy { $0.frequency.map { abs(Notes.midi($0)-midi)<12 } ?? true })
        }
    }
    func testSilenceAndNoise() {
        for noise in [false,true] {
            var seed:UInt64=12345; var frames:[AnalysisFrame]=[]; let pipe=PitchPipeline(rate:48000) { frames.append($0) }
            let samples:[Float]=(0..<24000).map { _ in seed=(1664525*seed+1013904223)&0xffffffff; return noise ? Float((Double(seed)/4294967296-0.5)*0.5):0 }
            pipe.accept(samples); XCTAssertTrue(frames.allSatisfy { $0.frequency==nil })
        }
    }
    func testSharedSegmentation() throws {
        let expected=Dictionary(uniqueKeysWithValues:try csv("expected-events").map { ($0[0],$0) })
        for (name,rows) in Dictionary(grouping:try csv("events"),by:{ $0[0] }) {
            let e=PracticeEngine()
            for r in rows { let f=Double(r[2])!; e.accept(AnalysisFrame(timeMs:Double(r[1])!,frequency:f>0 ? f:nil,confidence:Double(r[3])!,levelDb:Double(r[4])!,onset:r[5]=="1")) }
            let exp=expected[name]!, notes=exp[1].split(separator:";").map { Int($0)! }
            XCTAssertEqual(e.state.history.map { $0.midi },notes,name)
            if e.state.history.count==notes.count {
                for i in notes.indices { XCTAssertEqual(e.state.history[i].startMs,Double(exp[2].split(separator:";")[i])!,accuracy:150,name); XCTAssertEqual(e.state.history[i].endMs,Double(exp[3].split(separator:";")[i])!,accuracy:150,name) }
            }
        }
    }
    func testSuccessRequiresArticulation() {
        let e=PracticeEngine(); e.configure(mode:.selected)
        feed(e,0,800); XCTAssertTrue(e.state.success); XCTAssertEqual(e.state.successes,1)
        feed(e,800,1000,71); feed(e,1000,1800); XCTAssertEqual(e.state.successes,1)
        feed(e,1800,1860,nil); feed(e,1860,2500); XCTAssertEqual(e.state.successes,1)
        feed(e,2500,3300,onset:true); XCTAssertEqual(e.state.successes,2)
    }
    func testHoldResetsAndWrongOctave() {
        let e=PracticeEngine(); e.configure(mode:.selected)
        feed(e,0,400); feed(e,400,420,nil); feed(e,420,800); XCTAssertFalse(e.state.success)
        feed(e,800,900,cents:25); feed(e,900,1300); XCTAssertFalse(e.state.success)
        feed(e,1300,1500,81); XCTAssertEqual(e.live?.label,"Wrong note"); XCTAssertEqual(e.live!.cents,1200,accuracy:0.01)
    }
    func testScoreConsumesOnceAndRetainsTarget() {
        let e=PracticeEngine(); e.configure(mode:.score,scoreIndex:1)
        feed(e,0,400,67); XCTAssertEqual(e.cursor,0)
        feed(e,400,1200,69,cents:25); XCTAssertEqual(e.cursor,1); XCTAssertEqual(e.live?.target,69)
        feed(e,1200,1600,69,onset:true); XCTAssertEqual(e.cursor,2); XCTAssertEqual(e.live?.target,69); XCTAssertEqual(e.live?.label,"In tune")
        e.navigate(0); feed(e,1600,2000); XCTAssertEqual(e.cursor,0)
        e.stop(); e.reset(); XCTAssertEqual(e.cursor,0); XCTAssertNil(e.live); XCTAssertTrue(e.state.history.isEmpty)
    }
    func testCompletionAndBounds() {
        let e=PracticeEngine(); e.configure(mode:.score)
        for (i,n) in e.score.notes.enumerated() { feed(e,i*300,(i+1)*300,n.midi) }
        XCTAssertTrue(e.state.complete); XCTAssertEqual(e.live?.target,72)
        e.configure(); for i in 0...20 { feed(e,i*300,(i+1)*300,i%2==0 ? 69:71) }
        e.stop(); XCTAssertEqual(e.state.history.count,16)
    }
    func testSharedScores() throws {
        for (i,r) in try csv("scores").enumerated() { XCTAssertEqual(Scores.all[i].id,r[0]); XCTAssertEqual(Scores.all[i].notes.map { String($0.midi) }.joined(separator:";"),r[1]); XCTAssertEqual(Scores.all[i].notes.map { String($0.beats) }.joined(separator:";"),r[2]) }
    }
    func testAudioRearticulation() {
        var frames:[AnalysisFrame]=[]; let pipe=PitchPipeline(rate:48000) { frames.append($0) }
        pipe.accept((0..<48000).map { i in let t=Double(i)/48000, amplitude=t>=0.35 && t<0.42 ? 0.04:0.4; return Float(amplitude*sin(2*Double.pi*440*t)) })
        XCTAssertTrue(frames.contains { $0.onset && (420...480).contains($0.timeMs) })
        let e=PracticeEngine(); frames.forEach { e.accept($0) }; e.stop(); XCTAssertEqual(e.state.history.count,2)
    }
    func testWaveformToHistoryAndSilenceLatency() {
        var frames:[AnalysisFrame]=[]; let pipeline=PitchPipeline(rate:48000) { frames.append($0) }
        let samples:[Float]=(0..<120000).map { i in
            let t=Double(i)/48000
            let hz=t<0.65 ? 440.0:t<0.85 ? 0.0:t<1.5 ? 440.0:t<2.15 ? Notes.hz(71):0.0
            return Float(0.4*sin(2*Double.pi*hz*t))
        }
        pipeline.accept(samples); let e=PracticeEngine(); frames.forEach { e.accept($0) }
        XCTAssertEqual(e.state.history.map { $0.midi },[69,69,71]); XCTAssertNil(e.live)
        XCTAssertLessThanOrEqual(frames.first { $0.frequency != nil }!.timeMs,250)
        XCTAssertTrue(frames.contains { (2150...2650).contains($0.timeMs) && $0.frequency==nil })
        XCTAssertTrue(e.state.history.allSatisfy { abs($0.medianCents)<5 })
    }
    func testAudioVibrato() {
        let e=PracticeEngine(); let pipe=PitchPipeline(rate:48000) { e.accept($0) }; var phase=0.0
        pipe.accept((0..<48000).map { i in
            let hz=440*pow(2,25*sin(2*Double.pi*5*Double(i)/48000)/1200)
            phase+=2*Double.pi*hz/48000; return Float(0.4*sin(phase))
        })
        e.stop(); XCTAssertEqual(e.state.history.count,1)
    }
    func testConfidenceAndHysteresis() {
        let e=PracticeEngine(); feed(e,0,300)
        for t in stride(from:300,to:700,by:20) { feed(e,t,t+20,cents:t%40==0 ? 49:55) }
        e.stop(); XCTAssertEqual(e.state.history.count,1)
        e.reset()
        for t in stride(from:0,through:300,by:20) { e.accept(AnalysisFrame(timeMs:Double(t),frequency:440,confidence:0.2,levelDb:-20)) }
        XCTAssertNil(e.live); XCTAssertTrue(e.state.history.isEmpty)
    }

}
