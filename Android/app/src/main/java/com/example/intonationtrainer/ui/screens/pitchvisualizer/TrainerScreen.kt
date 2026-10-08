package com.example.intonationtrainer.ui.screens.pitchvisualizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.intonationtrainer.TrainerViewModel
import com.example.intonationtrainer.core.practice.*
import com.example.intonationtrainer.core.session.MicrophoneSession
import kotlin.math.abs

@Composable
fun TrainerScreen(model:TrainerViewModel,permissionPending:Boolean,onToggle:()->Unit,onSettings:()->Unit) {
    val p=model.practice; val mic=model.microphone
    val listening=mic.phase==MicrophoneSession.Phase.LISTENING
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("Intonation Trainer",style=MaterialTheme.typography.headlineMedium)
            Text("One note at a time · A4 = 440 Hz",style=MaterialTheme.typography.bodyMedium)
            Choice("Practice mode",p.mode.title,PracticeMode.entries.map { it.title }) { model.configure(mode=PracticeMode.entries[it]) }
            if(p.mode==PracticeMode.SELECTED) {
                Choice("Target note",Notes.name(p.selected),(40..84).map(Notes::name)) { model.configure(selected=it+40) }
                Text(if(p.success) "Well played! Articulate a new note to try again." else "Hold the target within ±10 cents for 0.5 seconds.")
                LinearProgressIndicator(progress={ if(p.success) 1f else p.holdProgress.toFloat() },modifier=Modifier.fillMaxWidth())
                Text("Successful holds: ${p.successes}")
            }
            if(p.mode==PracticeMode.SCORE) {
                val score=Scores.all[p.scoreIndex]
                Choice("Exercise",score.title,Scores.all.map { it.title }) { model.configure(scoreIndex=it) }
                Text(if(p.complete) "Exercise complete!" else "Next: ${Notes.name(score.notes[p.cursor].midi)} · ${p.cursor+1}/${score.notes.size}",style=MaterialTheme.typography.titleMedium)
                ScoreStaff(score,p.cursor)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={model.navigate(p.cursor-1)},enabled=p.cursor>0) { Text("Previous") }
                    OutlinedButton(onClick={model.navigate(p.cursor+1)},enabled=!p.complete) { Text("Next") }
                    TextButton(onClick={model.navigate(0)}) { Text("Restart") }
                }
                Text("Play at your own pace. Repeat notes with a new articulation.",style=MaterialTheme.typography.bodySmall)
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    val live=if(listening) p.live else null
                    if(live==null) Text(if(listening) "No clear note" else "Ready when you are",style=MaterialTheme.typography.headlineSmall)
                    else {
                        val color=when { live.midi!=live.target || abs(live.cents)>20 -> MaterialTheme.colorScheme.error; abs(live.cents)>10 -> Color(0xFFB06C00); else -> Color(0xFF227A47) }
                        Text(Notes.name(live.midi),style=MaterialTheme.typography.displayMedium)
                        Text("%.1f Hz · %+.1f cents".format(live.frequency,live.cents))
                        Text("${live.label} · target ${Notes.name(live.target)}",color=color,style=MaterialTheme.typography.titleMedium)
                        CentsMeter(live.cents,color)
                    }
                    Text(if(permissionPending) "Waiting for microphone permission…" else mic.message)
                    if(listening) Text("Input: %.0f dBFS".format(mic.levelDb),style=MaterialTheme.typography.bodySmall)
                }
            }
            val active=listening || mic.phase==MicrophoneSession.Phase.STARTING
            Button(onClick=onToggle,enabled=!permissionPending,modifier=Modifier.fillMaxWidth()) { Text(if(active) "Stop listening" else if(mic.phase==MicrophoneSession.Phase.ERROR) "Retry" else "Start listening") }
            if(mic.phase==MicrophoneSession.Phase.ERROR) TextButton(onClick=onSettings) { Text("Open Settings") }
            Text("Recent notes",style=MaterialTheme.typography.titleLarge)
            if(p.history.isEmpty()) Text("Completed notes will appear here.")
            p.history.reversed().forEach { event ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(Notes.name(event.midi))
                    Text("%.2f s · %+.1f ¢ vs %s".format(event.durationMs/1000,event.medianCents,Notes.name(event.target)))
                }
            }
        }
    }
}

@Composable private fun Choice(label:String,value:String,choices:List<String>,onSelect:(Int)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()) { Text("$label: $value") }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false},modifier=Modifier.heightIn(max=320.dp)) {
            choices.forEachIndexed { i,title -> DropdownMenuItem(text={Text(title)},onClick={expanded=false;onSelect(i)}) }
        }
    }
}
@Composable private fun CentsMeter(cents:Double,color:Color) {
    val track=MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(44.dp).semantics { contentDescription="Cents meter: ${cents.toInt()} cents, centered at zero" }) {
        val y=size.height/2
        drawLine(track,Offset(0f,y),Offset(size.width,y),4.dp.toPx())
        drawLine(color,Offset(size.width/2,0f),Offset(size.width/2,size.height),2.dp.toPx())
        val x=((cents.coerceIn(-50.0,50.0)+50)/100*size.width).toFloat()
        drawCircle(color,7.dp.toPx(),Offset(x.coerceIn(7.dp.toPx(),size.width-7.dp.toPx()),y))
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("−50 ¢");Text("0");Text("+50 ¢") }
}
@Composable private fun ScoreStaff(score:PracticeScore,cursor:Int) {
    val scroll=rememberScrollState()
    val density=androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(cursor,score.id) { scroll.animateScrollTo(with(density) { (maxOf(0,cursor-2)*56).dp.roundToPx() }) }
    Row(Modifier.horizontalScroll(scroll),verticalAlignment=Alignment.CenterVertically) {
        Text("𝄞",style=MaterialTheme.typography.displayLarge)
        score.notes.forEachIndexed { index,note ->
            val color=if(index==cursor) MaterialTheme.colorScheme.primary else if(index<cursor) Color(0xFF227A47) else MaterialTheme.colorScheme.onSurface
            val lines=MaterialTheme.colorScheme.outline
            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                Canvas(Modifier.width(56.dp).height(120.dp).semantics { contentDescription="${Notes.name(note.midi)}, ${note.beats} beat${if(note.beats==1) "" else "s"}${if(index==cursor) ", next note" else ""}" }) {
                    val step=5.dp.toPx(); val top=35.dp.toPx(); val bottom=top+8*step
                    for(i in 0..4) drawLine(lines,Offset(0f,top+i*2*step),Offset(size.width,top+i*2*step),1.dp.toPx())
                    val position=Notes.staffStep(note.midi)-30
                    val y=bottom-position*step; val x=size.width/2
                    if(position<0) for(k in -2 downTo position step 2) drawLine(color,Offset(x-11.dp.toPx(),bottom-k*step),Offset(x+11.dp.toPx(),bottom-k*step),1.dp.toPx())
                    if(position>8) for(k in 10..position step 2) drawLine(color,Offset(x-11.dp.toPx(),bottom-k*step),Offset(x+11.dp.toPx(),bottom-k*step),1.dp.toPx())
                    val head=Size(14.dp.toPx(),9.dp.toPx()); val origin=Offset(x-head.width/2,y-head.height/2)
                    if(note.beats==2) drawOval(color,origin,head,style=Stroke(2.dp.toPx())) else drawOval(color,origin,head)
                    val up=position<4; val stemX=x+if(up) head.width/2 else -head.width/2
                    drawLine(color,Offset(stemX,y),Offset(stemX,y+if(up) -28.dp.toPx() else 28.dp.toPx()),1.5.dp.toPx())
                }
                Text(Notes.name(note.midi),color=color,style=MaterialTheme.typography.labelMedium)
            }
        }
    }
}
