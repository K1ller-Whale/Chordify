package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.music.chordName
import com.arbaba.chordifymobile.music.functionHue
import com.arbaba.chordifymobile.music.isNoChord
import com.arbaba.chordifymobile.playback.PlaybackClock
import com.arbaba.chordifymobile.ui.LocalChordifyColors
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

// The timeline (Timeline.tsx): the whole song as an overview strip, and a detail track that
// moves under a fixed playhead. Sizes are the web's, in dp.

private const val HEIGHT = 196f
private const val SEC_Y = 6f
private const val SEC_H = 22f
private const val BEAT_Y = 34f
private const val RIB_Y = 44f
private const val RIB_H = 64f
private const val WAVE_Y = 118f
private const val WAVE_H = 70f

/** The detail track is drawn once per chord and moved by a graphics layer every frame, so the
 *  clock's [time] is read only by that layer and by the overview (spec §6.3). */
@Composable
fun Timeline(result: AnalysisResult, clock: PlaybackClock, time: State<Double>, current: Int, notation: Notation,
             peaks: FloatArray?, surprising: Set<Int>, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle("Timeline")
            Overview(result, clock, time)
            Detail(result, clock, time, current, notation, peaks, surprising)
            Legend()
        }
    }
}

@Composable
private fun Overview(result: AnalysisResult, clock: PlaybackClock, time: State<Double>) {
    val colors = LocalChordifyColors.current
    val ink = MaterialTheme.colorScheme.onSurface
    val ink2 = MaterialTheme.colorScheme.onSurfaceVariant
    val duration = result.source.duration
    Canvas(Modifier.fillMaxWidth().height(34.dp).testTag("overview")
               .semantics { contentDescription = "The whole song: tap or drag to jump" }
               .pointerInput(duration) { detectTapGestures { clock.seek(overviewSeek(it.x, size.width.toFloat(), duration)) } }
               .pointerInput(duration) {
                   detectHorizontalDragGestures { change, _ -> clock.seek(overviewSeek(change.position.x, size.width.toFloat(), duration)) }
               }) {
        val unit = size.width / 1000f // the web's viewBox is 1000 wide
        fun sx(t: Double) = (t / duration * size.width).toFloat()
        for (chord in result.chords) {
            val color = if (isNoChord(chord.label)) colors.grid else colors.of(functionHue(chord.function))
            drawRect(color, Offset(sx(chord.start), 12.dp.toPx()), Size(max(0.5f * unit, sx(chord.end) - sx(chord.start) - 0.6f * unit), 18.dp.toPx()))
        }
        for (section in result.sections) drawRect(ink2, Offset(sx(section.start), 0f), Size(0.8f * unit, size.height))
        val now = time.value
        val pps = PIXELS_PER_SECOND.dp.toPx()
        val shown = size.width / pps // seconds the detail track shows; it is as wide as this strip
        drawRoundRect(ink, Offset(sx(now - shown * PLAYHEAD), 9.dp.toPx()), Size(sx(shown.toDouble()), 24.dp.toPx()),
                      CornerRadius(3.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        drawRect(ink, Offset(sx(now), 0f), Size(1.5.dp.toPx(), size.height))
    }
}

@Composable
private fun Detail(result: AnalysisResult, clock: PlaybackClock, time: State<Double>, current: Int, notation: Notation,
                   peaks: FloatArray?, surprising: Set<Int>) {
    val colors = LocalChordifyColors.current
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val duration = result.source.duration
    val names = remember(result, notation, measurer) {
        result.chords.map { measurer.measure(chordName(it, notation), TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)) }
    }
    val numerals = remember(result, notation, measurer) {
        result.chords.map { measurer.measure((if (notation == Notation.ROMAN) it.display else it.roman).orEmpty(), TextStyle(fontSize = 12.sp)) }
    }
    val sections = remember(result, measurer) {
        result.sections.map { measurer.measure("${it.letter} ${it.label}", TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold)) }
    }
    val barNumbers = remember(result, measurer) {
        result.downbeats.indices.filter { it % 4 == 0 }.associateWith { measurer.measure("${it + 1}", TextStyle(fontSize = 9.sp)) }
    }
    val star = remember(measurer) { measurer.measure("✦", TextStyle(fontSize = 13.sp)) }
    val pps = with(LocalDensity.current) { PIXELS_PER_SECOND.dp.toPx() }
    BoxWithConstraints(Modifier.fillMaxWidth().height(HEIGHT.dp).clip(RoundedCornerShape(8.dp)).clipToBounds()
                           .background(scheme.surface)
                           .testTag("timeline")
                           .semantics { contentDescription = "Timeline of ${result.chords.size} chords: tap or drag to move through the song" }
                           .pointerInput(pps) { detectTapGestures { clock.seek(detailSeek(clock.time.value, it.x, size.width.toFloat(), pps)) } }
                           .pointerInput(pps) { detectHorizontalDragGestures { _, dx -> clock.seek(clock.time.value - dx / pps) } }) {
        val width = constraints.maxWidth.toFloat()
        Canvas(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width((duration * PIXELS_PER_SECOND).dp).height(HEIGHT.dp)
                   .graphicsLayer { translationX = width * PLAYHEAD - time.value.toFloat() * pps }) {
            val u = density
            fun y(dp: Float) = dp * u
            for ((i, section) in result.sections.withIndex()) {
                drawRoundRect(if (i % 2 == 1) scheme.surfaceVariant else colors.grid, Offset(section.start.toFloat() * pps + u, y(SEC_Y)),
                              Size(((section.end - section.start).toFloat() * pps - 2 * u).coerceAtLeast(0f), y(SEC_H)), CornerRadius(4 * u))
                drawText(sections[i], scheme.onSurfaceVariant, Offset(section.start.toFloat() * pps + 10 * u, y(SEC_Y + 15) - sections[i].firstBaseline))
            }
            for (beat in result.beats) {
                val x = beat.toFloat() * pps
                drawLine(colors.grid, Offset(x, y(BEAT_Y)), Offset(x, y(HEIGHT - 4)), u)
            }
            for ((i, beat) in result.downbeats.withIndex()) {
                val x = beat.toFloat() * pps
                drawLine(scheme.outline, Offset(x, y(BEAT_Y - 2)), Offset(x, y(HEIGHT - 4)), u)
                barNumbers[i]?.let { drawText(it, colors.muted, Offset(x + 3 * u, y(BEAT_Y + 7) - it.firstBaseline)) }
            }
            for ((i, chord) in result.chords.withIndex()) {
                val none = isNoChord(chord.label)
                val color = colors.of(functionHue(chord.function))
                val active = i == current
                val x = chord.start.toFloat() * pps + u
                val w = max(2 * u, (chord.end - chord.start).toFloat() * pps - 2 * u)
                val alpha = if (chord.confidence < 0.35 && !none) 0.6f else 1f
                val top = y(RIB_Y)
                val height = y(RIB_H)
                if (chord.function == "borrowed") {
                    hatch(colors.borrowed, x, top, w, height, 6 * u, alpha)
                } else {
                    val fill = when {
                        none -> scheme.surfaceVariant
                        active -> color
                        else -> lerp(scheme.surface, color, colors.tint)
                    }
                    drawRoundRect(fill, Offset(x, top), Size(w, height), CornerRadius(6 * u), alpha = alpha)
                }
                drawRoundRect(if (none) scheme.outline else color, Offset(x, top + height - 5 * u), Size(w, 5 * u), CornerRadius(2 * u), alpha = alpha)
                val ink = if (active && !none) Color.White else scheme.onSurface
                if (w > 34 * u) text(names[i], ink, x + 10 * u, y(RIB_Y + 26))
                if (w > 60 * u && !none) text(numerals[i], if (active) Color.White else scheme.onSurfaceVariant, x + 10 * u, y(RIB_Y + 45))
                if (chord.index in surprising) text(star, ink, x + w - 18 * u, y(RIB_Y + 20))
            }
            if (peaks != null && peaks.isNotEmpty()) {
                val step = (duration / peaks.size).toFloat()
                val stroke = max(u, step * pps * 0.55f)
                val middle = y(WAVE_Y + WAVE_H / 2)
                for ((i, peak) in peaks.withIndex()) {
                    val a = max(0.5f * u, peak * y(WAVE_H / 2 - 2))
                    val x = i * step * pps
                    drawLine(scheme.outline, Offset(x, middle - a), Offset(x, middle + a), stroke, StrokeCap.Round)
                }
            }
        }
        Box(Modifier.offset { IntOffset((width * PLAYHEAD).roundToInt(), 0) }.width(2.dp).fillMaxHeight().background(scheme.onSurface))
    }
}

/** Text whose baseline is at [baseline]. */
private fun DrawScope.text(layout: TextLayoutResult, color: Color, x: Float, baseline: Float) =
    drawText(layout, color, Offset(x, baseline - layout.firstBaseline))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend() {
    val colors = LocalChordifyColors.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((name, color) in listOf("Tonic" to colors.tonic, "Subdominant" to colors.subdominant, "Dominant" to colors.dominant)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
                Text(name, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Canvas(Modifier.size(10.dp)) { hatch(colors.borrowed, 0f, 0f, size.width, size.height, 3 * density, 1f) }
            Text("Outside the key", style = MaterialTheme.typography.bodySmall)
        }
        Text("✦ Surprising change", style = MaterialTheme.typography.bodySmall)
    }
}
