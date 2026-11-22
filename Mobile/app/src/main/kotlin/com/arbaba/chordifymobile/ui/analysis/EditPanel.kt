package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.arbaba.chordifymobile.edit.MIN_SPAN_SECONDS
import com.arbaba.chordifymobile.edit.stepToBeat
import com.arbaba.chordifymobile.engine.model.ChordSegment
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.music.chordName
import com.arbaba.chordifymobile.ui.SectionTitle
import java.util.Locale
import kotlin.math.floor

/** m:ss.cc, centiseconds truncated (0:42.10). */
internal fun formatPrecise(seconds: Double): String {
    val centis = floor(seconds.coerceAtLeast(0.0) * 100 + 1e-6).toLong()
    return String.format(Locale.US, "%d:%02d.%02d", centis / 6000, centis / 100 % 60, centis % 100)
}

/** The panel under the strip while editing: the selected chord's times and its actions. [chord] is the
 *  chord as shown (with the capo); times are seconds. [index] of [count] is its place in the song. */
@Composable
fun EditPanel(chord: ChordSegment, index: Int, count: Int, notation: Notation, beats: List<Double>,
              time: () -> Double, onSetStart: (Double) -> Unit, onSetEnd: (Double) -> Unit, onSplit: () -> Unit,
              onMergeWithNext: () -> Unit, onChangeChord: () -> Unit, onEditShape: () -> Unit,
              modifier: Modifier = Modifier) {
    val first = index == 0
    val last = index >= count - 1
    val startEarlier = stepToBeat(chord.start, beats, -1)
    val startLater = stepToBeat(chord.start, beats, 1)
    val endEarlier = stepToBeat(chord.end, beats, -1)
    val endLater = stepToBeat(chord.end, beats, 1)
    val hasBefore = { t: Double -> beats.isEmpty() || beats.any { it < t - 1e-6 } }
    val hasAfter = { t: Double -> beats.isEmpty() || beats.any { it > t + 1e-6 } }
    // The playhead is read through derivedStateOf so only a change of answer recomposes.
    val startHereOk by remember(chord.end, first, time) { derivedStateOf { !first && time() < chord.end - MIN_SPAN_SECONDS } }
    val endHereOk by remember(chord.start, last, time) { derivedStateOf { !last && time() > chord.start + MIN_SPAN_SECONDS } }
    val splitOk by remember(chord.start, chord.end, time) {
        derivedStateOf { time().let { it > chord.start + MIN_SPAN_SECONDS && it < chord.end - MIN_SPAN_SECONDS } }
    }

    Column(modifier.fillMaxWidth().testTag("editPanel").verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(chordName(chord, notation), style = MaterialTheme.typography.headlineSmall)
        theoryLine(chord, notation)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        SectionTitle("Starts")
        Text(formatPrecise(chord.start), Modifier.testTag("editStart"), style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ onSetStart(startEarlier) }, Modifier.semantics { contentDescription = "Start one beat earlier" },
                enabled = !first && hasBefore(chord.start) && startEarlier >= 0.0) { Text("−beat", Modifier.clearAndSetSemantics {}) }
            OutlinedButton({ onSetStart(startLater) }, Modifier.semantics { contentDescription = "Start one beat later" },
                enabled = !first && startLater < chord.end - MIN_SPAN_SECONDS && hasAfter(chord.start)) { Text("+beat", Modifier.clearAndSetSemantics {}) }
        }
        OutlinedButton({ onSetStart(time()) }, enabled = startHereOk) { Text("Set start to here") }

        SectionTitle("Ends")
        Text(formatPrecise(chord.end), Modifier.testTag("editEnd"), style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ onSetEnd(endEarlier) }, Modifier.semantics { contentDescription = "End one beat earlier" },
                enabled = !last && endEarlier > chord.start + MIN_SPAN_SECONDS && hasBefore(chord.end)) { Text("−beat", Modifier.clearAndSetSemantics {}) }
            OutlinedButton({ onSetEnd(endLater) }, Modifier.semantics { contentDescription = "End one beat later" },
                enabled = !last && hasAfter(chord.end)) { Text("+beat", Modifier.clearAndSetSemantics {}) }
        }
        OutlinedButton({ onSetEnd(time()) }, enabled = endHereOk) { Text("Set end to here") }

        SectionTitle("Chord")
        OutlinedButton(onSplit, enabled = splitOk) { Text("Split here") }
        OutlinedButton(onMergeWithNext, enabled = !last) { Text("Merge with next") }
        Button(onChangeChord) { Text("Change chord…") }
        OutlinedButton(onEditShape) { Text("Edit shape…") }
    }
}
