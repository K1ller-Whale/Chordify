package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.music.formatTime
import com.arbaba.chordifymobile.music.functionHue
import com.arbaba.chordifymobile.music.isNoChord
import com.arbaba.chordifymobile.playback.PlaybackClock
import com.arbaba.chordifymobile.ui.LocalChordifyColors
import kotlin.math.max

/** Play/pause, the time, the Loop chord chip (only when [looping] is not null), the seek bar (the whole song in function colours) and the speed chip.
 *  [visibleSeconds] is how many seconds the strip shows, outlined on the seek bar. */
@Composable
fun TransportBar(result: AnalysisResult, time: State<Double>, playing: Boolean, rate: Double,
                 visibleSeconds: Double, onToggle: () -> Unit, onSeek: (Double) -> Unit,
                 onRate: (Double) -> Unit, modifier: Modifier = Modifier,
                 looping: Boolean? = null, onLoop: () -> Unit = {}) {
    // Below 480 dp (a phone held upright) the Loop chord chip gets a row of its own above, and the parts of
    // the main row are 8 dp apart, so that the seek bar keeps 120 dp on a 360 dp-wide phone.
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val stacked = looping != null && maxWidth < LOOP_INLINE_MIN_WIDTH
    Column {
    if (stacked && looping != null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { LoopChip(looping, onLoop) }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledIconButton(onClick = onToggle, modifier = Modifier.testTag("play").semantics { contentDescription = if (playing) "Pause" else "Play" }) {
            // The core icon set has a play icon but no pause icon, so pause is a text glyph. A "▶"
            // glyph would be drawn as a coloured emoji on Samsung phones.
            if (playing) Text("❚❚", modifier = Modifier.clearAndSetSemantics {})
            else Icon(Icons.Filled.PlayArrow, contentDescription = null)
        }
        TimeText(time, result.source.duration)
        if (looping != null && !stacked) LoopChip(looping, onLoop)
        SeekBar(result, time, visibleSeconds, onSeek, Modifier.weight(1f))
        SpeedChip(rate, onRate)
    }
    }
    }
}

/** From this width on the Loop chord chip shares the main row; below it the chip has a row of its own. */
private val LOOP_INLINE_MIN_WIDTH = 480.dp

@Composable
private fun LoopChip(looping: Boolean, onLoop: () -> Unit) {
    FilterChip(selected = looping, onClick = onLoop, label = { Text("Loop chord", maxLines = 1) }, modifier = Modifier.testTag("loop"))
}

/** Its own scope, so that only this text follows the clock every frame. */
@Composable
private fun TimeText(time: State<Double>, duration: Double) {
    Text("${formatTime(time.value)} / ${formatTime(duration)}", style = MaterialTheme.typography.bodyMedium,
         modifier = Modifier.testTag("time"))
}

@Composable
private fun SeekBar(result: AnalysisResult, time: State<Double>, visibleSeconds: Double, onSeek: (Double) -> Unit, modifier: Modifier) {
    val colors = LocalChordifyColors.current
    val ink = MaterialTheme.colorScheme.onSurface
    val ink2 = MaterialTheme.colorScheme.onSurfaceVariant
    val duration = result.source.duration
    Canvas(modifier.height(24.dp).clipToBounds().testTag("seekBar")
               .semantics { contentDescription = "The whole song: tap or drag to jump" }
               .pointerInput(duration) { detectTapGestures { onSeek(overviewSeek(it.x, size.width.toFloat(), duration)) } }
               .pointerInput(duration) {
                   detectHorizontalDragGestures { change, _ -> onSeek(overviewSeek(change.position.x, size.width.toFloat(), duration)) }
               }) {
        fun sx(t: Double) = (t / duration * size.width).toFloat()
        for (chord in result.chords) {
            val color = if (isNoChord(chord.label)) colors.grid else colors.of(functionHue(chord.function))
            drawRect(color, Offset(sx(chord.start), 6.dp.toPx()), Size(max(0.5f, sx(chord.end) - sx(chord.start) - 0.6f), 12.dp.toPx()))
        }
        for (section in result.sections) drawRect(ink2, Offset(sx(section.start), 0f), Size(1.dp.toPx(), size.height))
        val now = time.value
        drawRoundRect(ink.copy(alpha = 0.6f), Offset(sx(now - visibleSeconds * PLAYHEAD), 3.dp.toPx()),
                      Size(sx(visibleSeconds), size.height - 6.dp.toPx()), CornerRadius(3.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        drawRect(ink, Offset(sx(now) - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
    }
}

@Composable
private fun SpeedChip(rate: Double, onRate: (Double) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text(speedLabel(rate) + " ▾") }, modifier = Modifier.testTag("speed"))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (speed in PlaybackClock.SPEEDS) {
                DropdownMenuItem(text = { Text(speedLabel(speed)) }, onClick = { onRate(speed); open = false })
            }
        }
    }
}
