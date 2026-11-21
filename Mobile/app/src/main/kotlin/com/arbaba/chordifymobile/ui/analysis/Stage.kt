package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import com.arbaba.chordifymobile.engine.model.ChordSegment
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.music.chordName
import com.arbaba.chordifymobile.music.guitarShape
import com.arbaba.chordifymobile.music.isNoChord
import com.arbaba.chordifymobile.ui.LocalChordifyColors

/** The play-along stage: [chord] as the notation shows it, its theory and capo lines, the unsure
 *  badge, its guitar shape. A horizontal swipe calls onSwipe(+1) for the next chord (swipe to the
 *  left) or onSwipe(-1) for the previous one. [sounding] is the chord as it sounds without the capo. */
@Composable
fun Stage(chord: ChordSegment, notation: Notation, capo: Int, sounding: String?,
          onSwipe: (delta: Int) -> Unit, modifier: Modifier = Modifier) {
    val muted = LocalChordifyColors.current.muted
    val noChord = isNoChord(chord.label)
    val doubt = unsure(chord)
    var showDoubt by remember(chord.index) { mutableStateOf(false) }
    val swipe = Modifier.pointerInput(onSwipe) {
        var moved = 0f
        detectHorizontalDragGestures(onDragStart = { moved = 0f }, onDragEnd = {
            if (abs(moved) > 48.dp.toPx()) onSwipe(if (moved < 0) 1 else -1)
        }) { _, dx -> moved += dx }
    }
    Box(modifier.fillMaxSize().then(swipe).testTag("stage").semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(chordName(chord, notation), fontSize = 64.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("nowName"))
                    if (doubt != null) {
                        Box(Modifier.size(28.dp).border(1.dp, muted, CircleShape).clickable { showDoubt = !showDoubt }
                            .testTag("unsure")
                            .clearAndSetSemantics {
                                contentDescription = "Show other possible chords"
                                role = Role.Button
                                onClick { showDoubt = !showDoubt; true }
                            },
                            contentAlignment = Alignment.Center) {
                            Text("?", style = MaterialTheme.typography.labelLarge, color = muted)
                        }
                    }
                }
                theoryLine(chord, notation)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.testTag("theoryLine"))
                }
                soundsAsLine(chord, capo, sounding)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.testTag("soundsAs"))
                }
                if (doubt != null && showDoubt) {
                    Text(doubt, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("unsureText"))
                }
            }
            if (!noChord) GuitarDiagram(guitarShape(chord), capo, width = 96.dp)
        }
    }
}
