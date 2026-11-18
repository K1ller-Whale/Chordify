package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ChordSegment
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.music.chordName
import com.arbaba.chordifymobile.music.functionHue
import com.arbaba.chordifymobile.music.guitarShape
import com.arbaba.chordifymobile.music.isNoChord
import com.arbaba.chordifymobile.ui.LocalChordifyColors

@Composable
private fun CardTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = LocalChordifyColors.current.muted)
}

/** Now playing (NowAndNext.tsx): the chord, its numeral and function, what to play over it, the
 *  time to the change and its guitar shape. A polite live region, as the web's aria-live. */
@Composable
fun NowPlayingCard(chord: ChordSegment, result: AnalysisResult, time: () -> Double, notation: Notation, capo: Int, sounding: String?) {
    val colors = LocalChordifyColors.current
    Card(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CardTitle("Now playing")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(chordName(chord, notation), fontSize = 48.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("nowName"))
                    if (capo > 0 && !isNoChord(chord.label) && sounding != null) {
                        Text("Capo $capo · sounds as $sounding", style = MaterialTheme.typography.bodySmall)
                    }
                    if (isNoChord(chord.label)) {
                        Text("No chord", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(colors.of(functionHue(chord.function))))
                            Text((if (notation == Notation.ROMAN) chord.display else chord.roman).orEmpty(), fontWeight = FontWeight.Bold)
                            functionLine(chord, result)?.let { Text("· $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    chord.scaleHint?.let { Text("Scale to play over it: $it", style = MaterialTheme.typography.bodySmall) }
                    ChordProgress(chord, time)
                    ChangeCountdown(chord, result.beats, time)
                    unsure(chord)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (!isNoChord(chord.label)) GuitarDiagram(guitarShape(chord), capo, width = 96.dp)
            }
        }
    }
}

/** The progress bar and the countdown read the clock in their own scopes, so the rest of Now
 *  playing recomposes only when the chord changes (spec §6.3). */
@Composable
private fun ChordProgress(chord: ChordSegment, time: () -> Double) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(elapsed(chord, time())).height(6.dp).background(MaterialTheme.colorScheme.onSurfaceVariant))
    }
}

@Composable
private fun ChangeCountdown(chord: ChordSegment, beats: List<Double>, time: () -> Double) {
    Text(countdown(chord, beats, time()), style = MaterialTheme.typography.bodySmall)
}

/** What comes next (NowAndNext.tsx): the predictions with their reasons, the chord that really
 *  came next, and the prediction without the song's own history. */
@Composable
fun NextChordCard(chord: ChordSegment, actualNext: ChordSegment?, chordsByDisplay: Map<String, ChordSegment>, notation: Notation) {
    val colors = LocalChordifyColors.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle("What comes next")
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
                                    .background(colors.of(functionHue(chordsByDisplay[prediction.display]?.function))))
                        }
                        Text("${percent(prediction.p)}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text((if (k == 0) "Most likely · " else "") + prediction.reason, style = MaterialTheme.typography.bodySmall,
                         color = colors.muted, modifier = Modifier.padding(start = 76.dp))
                }
            }
            val actual = actualNextLine(chord, actualNext, notation)
            val theory = theoryOnlyLine(chord)
            if (actual != null || theory != null) {
                HorizontalDivider()
                actual?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                theory?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
