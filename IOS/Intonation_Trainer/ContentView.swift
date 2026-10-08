import SwiftUI

struct ContentView: View {
    @StateObject private var microphone = MicrophoneSession()
    @Environment(\.scenePhase) private var scenePhase
    var body: some View {
        let p=microphone.practice
        ScrollView {
            VStack(alignment:.leading,spacing:18) {
                Text("Intonation Trainer").font(.largeTitle)
                Text("One note at a time · A4 = 440 Hz").foregroundStyle(.secondary)
                Picker("Practice mode",selection:Binding(get:{p.mode},set:{microphone.configure(mode:$0)})) {
                    ForEach(PracticeMode.allCases,id:\.self) { Text($0.rawValue).tag($0) }
                }.pickerStyle(.menu)
                if p.mode == .selected {
                    Picker("Target note",selection:Binding(get:{p.selected},set:{microphone.configure(selected:$0)})) {
                        ForEach(40...84,id:\.self) { Text(Notes.name($0)).tag($0) }
                    }.pickerStyle(.menu)
                    Text(p.success ? "Well played! Articulate a new note to try again.":"Hold the target within ±10 cents for 0.5 seconds.")
                    ProgressView(value:p.success ? 1:p.holdProgress)
                    Text("Successful holds: \(p.successes)")
                }
                if p.mode == .score {
                    let score=Scores.all[p.scoreIndex]
                    Picker("Exercise",selection:Binding(get:{p.scoreIndex},set:{microphone.configure(scoreIndex:$0)})) {
                        ForEach(Scores.all.indices,id:\.self) { Text(Scores.all[$0].title).tag($0) }
                    }.pickerStyle(.menu)
                    Text(p.complete ? "Exercise complete!":"Next: \(Notes.name(score.notes[min(p.cursor,score.notes.count-1)].midi)) · \(p.cursor+1)/\(score.notes.count)").font(.headline)
                    ScoreStaff(score:score,cursor:p.cursor)
                    HStack {
                        Button("Previous") { microphone.navigate(p.cursor-1) }.disabled(p.cursor==0)
                        Button("Next") { microphone.navigate(p.cursor+1) }.disabled(p.complete)
                        Button("Restart") { microphone.navigate(0) }
                    }.buttonStyle(.bordered)
                    Text("Play at your own pace. Repeat notes with a new articulation.").font(.caption)
                }
                VStack(alignment:.leading,spacing:12) {
                    if microphone.phase == .listening,let live=p.live {
                        let color:Color=live.midi != live.target || abs(live.cents)>20 ? .red:abs(live.cents)>10 ? .orange:.green
                        Text(Notes.name(live.midi)).font(.system(size:52,weight:.semibold,design:.rounded))
                        Text(String(format:"%.1f Hz · %+.1f cents",live.frequency,live.cents)).monospacedDigit()
                        Text("\(live.label) · target \(Notes.name(live.target))").font(.headline).foregroundStyle(color)
                        CentsMeter(cents:live.cents,color:color)
                    } else {
                        Text(microphone.phase == .listening ? "No clear note":"Ready when you are").font(.title2)
                    }
                    Text(microphone.message)
                    if microphone.phase == .listening { Text("Input: \(microphone.levelDb,specifier:"%.0f") dBFS").font(.caption) }
                }.padding(20).frame(maxWidth:.infinity,alignment:.leading).background(Color.secondary.opacity(0.1)).cornerRadius(18)
                Button(microphone.isActive ? "Stop listening":microphone.phase == .error ? "Retry":"Start listening") {
                    if microphone.isActive { microphone.stop() } else { microphone.start() }
                }.buttonStyle(.borderedProminent)
                if microphone.phase == .error {
                    Button("Open Settings") { if let url=URL(string:UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } }
                }
                Text("Recent notes").font(.title2)
                if p.history.isEmpty { Text("Completed notes will appear here.").foregroundStyle(.secondary) }
                ForEach(p.history.reversed()) { event in
                    HStack {
                        Text(Notes.name(event.midi)); Spacer()
                        Text(String(format:"%.2f s · %+.1f ¢ vs %@",event.durationMs/1000,event.medianCents,Notes.name(event.target))).font(.subheadline).monospacedDigit()
                    }
                }
            }.padding(24)
        }
        .onChange(of:scenePhase) { phase in if phase == .background { microphone.stop(message:"Listening stopped. Tap Start listening to begin again.") } }
    }
}

private struct CentsMeter:View {
    let cents:Double; let color:Color
    var body:some View {
        VStack {
            GeometryReader { geometry in
                let width=geometry.size.width
                ZStack(alignment:.leading) {
                    Rectangle().fill(Color.secondary.opacity(0.3)).frame(height:4)
                    Rectangle().fill(color).frame(width:2,height:36).offset(x:width/2)
                    Circle().fill(color).frame(width:14,height:14).offset(x:max(0,min(width-14,CGFloat((max(-50,min(50,cents))+50)/100)*width-7)))
                }.frame(height:40)
            }.frame(height:40).accessibilityLabel("Cents meter: \(Int(cents)) cents, centered at zero")
            HStack { Text("−50 ¢");Spacer();Text("0");Spacer();Text("+50 ¢") }.font(.caption)
        }
    }
}

private struct ScoreStaff:View {
    let score:PracticeScore; let cursor:Int
    var body:some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                HStack(spacing:0) {
                    Text("𝄞").font(.system(size:58)).frame(width:36)
                    ForEach(score.notes) { note in
                        let color:Color=note.id==cursor ? .accentColor:note.id<cursor ? .green:.primary
                        VStack(spacing:0) {
                            Canvas { context,size in
                                let top=35.0,step=5.0,bottom=75.0,x=size.width/2
                                var staff=Path()
                                for i in 0...4 { staff.move(to:CGPoint(x:0,y:top+Double(i)*10));staff.addLine(to:CGPoint(x:size.width,y:top+Double(i)*10)) }
                                context.stroke(staff,with:.color(.secondary),lineWidth:1)
                                let position=Notes.staffStep(note.midi)-30, y=bottom-Double(position)*step
                                var ledger=Path()
                                let steps=position<0 ? Array(stride(from:-2,through:position,by:-2)):position>8 ? Array(stride(from:10,through:position,by:2)):[]
                                for k in steps { ledger.move(to:CGPoint(x:x-11,y:bottom-Double(k)*step));ledger.addLine(to:CGPoint(x:x+11,y:bottom-Double(k)*step)) }
                                context.stroke(ledger,with:.color(color),lineWidth:1)
                                let head=Path(ellipseIn:CGRect(x:x-7,y:y-4.5,width:14,height:9))
                                if note.beats==2 { context.stroke(head,with:.color(color),lineWidth:2) } else { context.fill(head,with:.color(color)) }
                                var stem=Path();let up=position<4, stemX=x+(up ? 7:-7)
                                stem.move(to:CGPoint(x:stemX,y:y));stem.addLine(to:CGPoint(x:stemX,y:y+(up ? -28:28)))
                                context.stroke(stem,with:.color(color),lineWidth:1.5)
                            }.frame(width:56,height:120)
                            Text(Notes.name(note.midi)).font(.caption).foregroundStyle(color)
                        }.id(note.id).accessibilityElement(children:.ignore).accessibilityLabel("\(Notes.name(note.midi)), \(note.beats) beats\(note.id==cursor ? ", next note":"")")
                    }
                }
            }.onChange(of:cursor) { value in withAnimation { proxy.scrollTo(min(value,score.notes.count-1),anchor:.center) } }
        }
    }
}
