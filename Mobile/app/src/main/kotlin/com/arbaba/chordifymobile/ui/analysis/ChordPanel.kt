package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ChordSegment
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.music.chordName
import com.arbaba.chordifymobile.music.functionHue
import com.arbaba.chordifymobile.music.isNoChord
import com.arbaba.chordifymobile.ui.LocalChordifyColors
import com.arbaba.chordifymobile.ui.SectionTitle

/** The Chord tab: Now (function in key, scale, countdown, doubt) and What comes next. Fills
 *  [modifier]'s space and scrolls vertically itself. */
@Composable
fun ChordPanel(chord: ChordSegment, result: AnalysisResult, time: () -> Double, notation: Notation,
               modifier: Modifier = Modifier) {
    val colors = LocalChordifyColors.current
    val byDisplay = remember(result) { result.chords.associateBy { it.display } }
    Column(modifier.fillMaxSize().testTag("chordPanel").verticalScroll(rememberScrollState()).padding(12.dp),
           verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Now")
        if (!isNoChord(chord.label)) {
            functionLine(chord, result)?.let { Text("${chordName(chord, notation)}: $it", fontWeight = FontWeight.SemiBold) }
        }
        chord.scaleHint?.let { Text("Scale to play over it: $it", style = MaterialTheme.typography.bodySmall) }
        Countdown(chord, result.beats, time)
        unsure(chord)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Column(Modifier.padding(top = 8.dp)) { SectionTitle("What comes next") }
        if (chord.next.isEmpty()) Text("Predictions appear once a chord is playing.", style = MaterialTheme.typography.bodySmall)
        for ((k, prediction) in chord.next.withIndex()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text((if (notation == Notation.ROMAN) prediction.roman else prediction.display).orEmpty(), fontSize = 22.sp,
                         fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp))
                    Text((if (notation == Notation.ROMAN) prediction.display else prediction.roman).orEmpty(),
                         color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(44.dp))
                    Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(prediction.p.toFloat().coerceIn(0f, 1f)).height(8.dp)
                                .background(colors.of(functionHue(byDisplay[prediction.display]?.function))))
                    }
                    Text("${percent(prediction.p)}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text((if (k == 0) "Most likely · " else "") + prediction.reason, style = MaterialTheme.typography.bodySmall,
                     color = colors.muted, modifier = Modifier.padding(start = 76.dp))
            }
        }
        val actual = actualNextLine(chord, actualNext(result.chords, chord), notation)
        val theory = theoryOnlyLine(chord)
        if (actual != null || theory != null) {
            HorizontalDivider()
            actual?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            theory?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Reads the clock here, so only this line recomposes every frame. */
@Composable
private fun Countdown(chord: ChordSegment, beats: List<Double>, time: () -> Double) {
    val text = countdown(chord, beats, time())
    if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodySmall)
}
