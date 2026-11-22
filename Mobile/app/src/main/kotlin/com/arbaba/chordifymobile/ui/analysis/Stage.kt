package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import com.arbaba.chordifymobile.engine.model.ChordSegment
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.music.chordName
import com.arbaba.chordifymobile.music.guitarShape
import com.arbaba.chordifymobile.music.isNoChord
import com.arbaba.chordifymobile.ui.LocalChordifyColors

/** The play-along stage: [chord] as the notation shows it, its theory and capo lines, the unsure
 *  badge, its guitar shape. A horizontal swipe calls onSwipe(+1) for the next chord (swipe to the
 *  left) or onSwipe(-1) for the previous one. [sounding] is the chord as it sounds without the capo.
 *  The name takes the largest display style that leaves room for its lines in the stage's height;
 *  when even the smallest does not, the whole stage is scaled down to fit, so nothing is cut. */
@Composable
fun Stage(chord: ChordSegment, notation: Notation, capo: Int, sounding: String?,
          onSwipe: (delta: Int) -> Unit, modifier: Modifier = Modifier) {
    val muted = LocalChordifyColors.current.muted
    val typography = MaterialTheme.typography
    val noChord = isNoChord(chord.label)
    val theory = theoryLine(chord, notation)
    val soundsAs = soundsAsLine(chord, capo, sounding)
    val doubt = unsure(chord)
    var showDoubt by remember(chord.index) { mutableStateOf(false) }
    val swipe = Modifier.pointerInput(onSwipe) {
        var moved = 0f
        detectHorizontalDragGestures(onDragStart = { moved = 0f }, onDragEnd = {
            if (abs(moved) > 48.dp.toPx()) onSwipe(if (moved < 0) 1 else -1)
        }) { _, dx -> moved += dx }
    }
    BoxWithConstraints(modifier.fillMaxSize().then(swipe).testTag("stage"), contentAlignment = Alignment.Center) {
        // The largest name that leaves room for the lines under it: a phone held sideways with the
        // no-audio banner above the stage leaves the stage about 100 dp.
        val density = LocalDensity.current
        fun TextStyle.line() = with(density) { lineHeight.toDp() }
        // One line of text is never shorter than its font's own height, about 1.2 times its size.
        fun TextStyle.nameLine() = maxOf(line(), with(density) { (fontSize * 1.2f).toDp() })
        val below = (if (theory != null) LINE_GAP + typography.bodyMedium.line() else 0.dp) +
            (if (soundsAs != null) LINE_GAP + typography.bodySmall.line() else 0.dp) +
            (if (doubt != null && showDoubt) LINE_GAP + typography.bodySmall.line() else 0.dp)
        val names = listOf(typography.displayLarge, typography.displayMedium, typography.displaySmall, typography.headlineLarge)
        val nameStyle = (names.firstOrNull { maxOf(it.nameLine(), if (doubt != null) BADGE_TARGET else 0.dp) + below <= maxHeight }
            ?: names.last()).copy(fontWeight = FontWeight.Bold)
        val diagramWidth = minOf(96.dp, maxHeight * 0.8f) // the diagram is 1.25 times as tall as wide
        ScaledToFit { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
                // Only the name and its lines are announced when the chord changes: the badge is a control
                // of its own (clickable merges its own semantics); its text and the diagram sit outside.
                Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("announce"),
                       verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(chordName(chord, notation), style = nameStyle, modifier = Modifier.testTag("nowName"))
                        if (doubt != null) {
                            // A 28 dp circle in a 48 dp touch target, centred on the name.
                            Box(Modifier.size(BADGE_TARGET).clip(CircleShape).clickable { showDoubt = !showDoubt }
                                    .testTag("unsure")
                                    .clearAndSetSemantics {
                                        contentDescription = "Show other possible chords"
                                        role = Role.Button
                                        onClick { showDoubt = !showDoubt; true }
                                    },
                                contentAlignment = Alignment.Center) {
                                Box(Modifier.size(28.dp).border(1.dp, muted, CircleShape), contentAlignment = Alignment.Center) {
                                    Text("?", style = typography.labelLarge, color = muted)
                                }
                            }
                        }
                    }
                    theory?.let { Text(it, style = typography.bodyMedium, color = muted, modifier = Modifier.testTag("theoryLine")) }
                    soundsAs?.let { Text(it, style = typography.bodySmall, color = muted, modifier = Modifier.testTag("soundsAs")) }
                }
                if (doubt != null && showDoubt) {
                    Text(doubt, style = typography.bodySmall, modifier = Modifier.testTag("unsureText"))
                }
            }
            if (!noChord) GuitarDiagram(guitarShape(chord), capo, width = diagramWidth)
        } }
    }
}

/** [content] at its own height, centred; scaled down (about its centre) when it is taller than the space. */
@Composable
private fun ScaledToFit(content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val placeable = measurables.single().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val height = if (constraints.hasBoundedHeight) minOf(placeable.height, constraints.maxHeight) else placeable.height
        val scale = if (placeable.height > height) height / placeable.height.toFloat() else 1f
        val width = placeable.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, height) {
            placeable.placeWithLayer((width - placeable.width) / 2, (height - placeable.height) / 2) {
                scaleX = scale
                scaleY = scale
            }
        }
    }
}

/** The space between the name and each line under it. */
private val LINE_GAP = 4.dp

/** The unsure badge's touch target. */
private val BADGE_TARGET = 48.dp
