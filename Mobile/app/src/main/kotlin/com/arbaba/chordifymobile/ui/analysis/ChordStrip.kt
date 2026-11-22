package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbaba.chordifymobile.edit.snapToBeat
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ChordSegment
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.music.chordName
import com.arbaba.chordifymobile.music.functionHue
import com.arbaba.chordifymobile.music.isNoChord
import com.arbaba.chordifymobile.ui.LocalChordifyColors
import kotlin.math.max
import kotlin.math.roundToInt

// Geometry in dp.
private const val HEIGHT = 72f
private const val EDIT_HEIGHT = 110f
private const val RIB_Y = 4f
private const val RIB_H = 46f
private const val EDIT_RIB_H = 84f
private const val WAVE_H = 16f
private const val HANDLE_REACH = 24f
private const val SNAP_DP = 6f

/** A chord's name on the strip: an edited chord ends with " ✎". */
fun stripName(chord: ChordSegment, notation: Notation): String =
    chordName(chord, notation) + if (chord.edited) " ✎" else ""

/** An edge being dragged: edge k is the boundary between chords k-1 and k. The finger is at [x] (px, in the strip)
 *  and grabbed the edge [grab] seconds away from the point under it, so the edge follows the finger as the strip moves. */
private class EdgeDrag(val edge: Int, val x: Float, val grab: Double)

/** The chords coming up, sliding toward a fixed playhead (the timeline's detail track, 72 dp tall). */
@Composable
fun ChordStrip(result: AnalysisResult, time: State<Double>, current: Int, notation: Notation,
               peaks: FloatArray?, surprising: Set<Int>, onSeek: (Double) -> Unit, modifier: Modifier = Modifier,
               editing: Boolean = false, selected: Int? = null, onSelect: (Int) -> Unit = {},
               onMoveEdge: (edge: Int, time: Double) -> Unit = { _, _ -> }) {
    val colors = LocalChordifyColors.current
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val duration = result.source.duration
    val names = remember(result, notation, measurer) {
        result.chords.map { measurer.measure(stripName(it, notation), TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)) }
    }
    val numerals = remember(result, notation, measurer) {
        result.chords.map { measurer.measure((if (notation == Notation.ROMAN) it.display else it.roman).orEmpty(), TextStyle(fontSize = 11.sp)) }
    }
    val star = remember(measurer) { measurer.measure("✦", TextStyle(fontSize = 12.sp)) }
    val pps = with(LocalDensity.current) { PIXELS_PER_SECOND.dp.toPx() }
    val onSeekNow by rememberUpdatedState(onSeek)
    val resultNow by rememberUpdatedState(result)
    val selectedNow by rememberUpdatedState(selected)
    val onSelectNow by rememberUpdatedState(onSelect)
    val onMoveEdgeNow by rememberUpdatedState(onMoveEdge)
    var drag by remember { mutableStateOf<EdgeDrag?>(null) }
    val stripHeight = if (editing) EDIT_HEIGHT else HEIGHT
    val ribH = if (editing) EDIT_RIB_H else RIB_H
    val waveY = stripHeight - 2 - WAVE_H
    val playheadFraction = PLAYHEAD
    // Where a drag puts its edge now: under the finger, whatever the strip has moved since, snapped and kept between its neighbours.
    fun edgeTime(d: EdgeDrag, widthPx: Float): Double {
        val chords = resultNow.chords
        val raw = time.value + (d.x - widthPx * playheadFraction) / pps + d.grab
        val snap = SNAP_DP / PIXELS_PER_SECOND.toDouble()
        return snapToBeat(raw.coerceIn(chords[d.edge - 1].start, chords[d.edge].end), resultNow.beats, snap)
    }
    BoxWithConstraints(modifier.fillMaxWidth().height(stripHeight.dp).clipToBounds()
                           .background(scheme.surface)
                           .testTag("strip")
                           .semantics {
                               if (editing) {
                                   contentDescription = "Chord strip, double-tap a chord to select it"
                                   // Without a pointer: the chord at the playhead and its neighbours.
                                   customActions = (current - 1..current + 2).filter { it in result.chords.indices }.map { i ->
                                       CustomAccessibilityAction("Select chord ${i + 1}, ${chordName(result.chords[i], notation)}") { onSelectNow(i); true }
                                   }
                               } else {
                                   contentDescription = "Timeline of ${result.chords.size} chords: tap or drag to move through the song"
                               }
                           }
                           .then(if (editing) {
                               Modifier.pointerInput(pps) {
                                   val reach = HANDLE_REACH.dp.toPx()
                                   awaitEachGesture {
                                       val down = awaitFirstDown()
                                       val chords = resultNow.chords
                                       val playheadX = size.width * PLAYHEAD
                                       fun xOf(t: Double) = playheadX + ((t - time.value) * pps).toFloat()
                                       // The edge whose handle is under the finger, if its block is selected.
                                       val handle = selectedNow?.let { i ->
                                           listOfNotNull(i.takeIf { it > 0 }, (i + 1).takeIf { it < chords.size })
                                               .filter { kotlin.math.abs(xOf(chords[it].start) - down.position.x) <= reach }
                                               .minByOrNull { kotlin.math.abs(xOf(chords[it].start) - down.position.x) }
                                       }
                                       val grab = handle?.let { chords[it].start - (time.value + (down.position.x - playheadX) / pps) } ?: 0.0
                                       var last = down.position.x
                                       val over = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                       if (over != null) {
                                           try {
                                               fun follow(x: Float) {
                                                   if (handle != null) drag = EdgeDrag(handle, x, grab)
                                                   else onSeekNow(time.value - (x - last) / pps)
                                                   last = x
                                               }
                                               follow(over.position.x)
                                               val finished = horizontalDrag(over.id) { change ->
                                                   change.consume()
                                                   follow(change.position.x)
                                               }
                                               val done = drag
                                               if (finished && handle != null && done != null) {
                                                   onMoveEdgeNow(done.edge, edgeTime(done, size.width.toFloat()))
                                               }
                                           } finally {
                                               drag = null
                                           }
                                       } else {
                                           // A tap: the finger came up, near where it went down, and nobody took the gesture.
                                           val up = currentEvent.changes.firstOrNull { it.id == down.id }
                                           if (up != null && !up.pressed && !up.isConsumed && chords.isNotEmpty() &&
                                               (up.position - down.position).getDistance() <= viewConfiguration.touchSlop) {
                                               val t = time.value + (down.position.x - playheadX) / pps
                                               val index = chords.indexOfFirst { t < it.end }.let { if (it < 0) chords.lastIndex else it }
                                               onSelectNow(index)
                                           }
                                       }
                                   }
                               }
                           } else {
                               Modifier.pointerInput(pps) { detectTapGestures { onSeekNow(detailSeek(time.value, it.x, size.width.toFloat(), pps)) } }
                                   .pointerInput(pps) { detectHorizontalDragGestures { _, dx -> onSeekNow(time.value - dx / pps) } }
                           })) {
        val width = constraints.maxWidth.toFloat()
        Canvas(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width((duration * PIXELS_PER_SECOND).dp).height(stripHeight.dp)
                   .graphicsLayer { translationX = width * PLAYHEAD - time.value.toFloat() * pps }) {
            val u = density
            fun y(dp: Float) = dp * u
            for (beat in result.beats) {
                val x = beat.toFloat() * pps
                drawLine(scheme.outline, Offset(x, 0f), Offset(x, size.height), u, alpha = 0.4f)
            }
            for (beat in result.downbeats) {
                val x = beat.toFloat() * pps
                drawLine(scheme.outline, Offset(x, 0f), Offset(x, size.height), 1.5f * u)
            }
            for (section in result.sections) {
                drawRect(scheme.onSurfaceVariant, Offset(section.start.toFloat() * pps, 0f), Size(2 * u, size.height))
            }
            if (peaks != null && peaks.isNotEmpty()) {
                val step = (duration / peaks.size).toFloat()
                val stroke = max(u, step * pps * 0.55f)
                val middle = y(waveY + WAVE_H / 2)
                for ((i, peak) in peaks.withIndex()) {
                    val a = max(0.5f * u, peak * y(WAVE_H / 2 - 1))
                    val x = i * step * pps
                    drawLine(scheme.outline, Offset(x, middle - a), Offset(x, middle + a), stroke, StrokeCap.Round, alpha = 0.5f)
                }
            }
            for ((i, chord) in result.chords.withIndex()) {
                val none = isNoChord(chord.label)
                val color = colors.of(functionHue(chord.function))
                val active = i == current
                val d = drag
                val dragged = d?.let { edgeTime(it, width) }
                val start = if (d != null && d.edge == i) dragged!! else chord.start
                val end = if (d != null && d.edge == i + 1) dragged!! else chord.end
                val x = start.toFloat() * pps + u
                val w = max(2 * u, (end - start).toFloat() * pps - 2 * u)
                val alpha = if (chord.confidence < 0.35 && !none) 0.6f else 1f
                val top = y(RIB_Y)
                val height = y(ribH)
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
                drawRoundRect(if (none) scheme.outline else color, Offset(x, top + height - 4 * u), Size(w, 4 * u), CornerRadius(2 * u), alpha = alpha)
                val ink = if (active && !none) Color.White else scheme.onSurface
                if (w > 34 * u) text(names[i], ink, x + 8 * u, y(22f))
                if (w > 60 * u && !none) text(numerals[i], if (active) Color.White else scheme.onSurfaceVariant, x + 8 * u, y(40f))
                if (chord.index in surprising) text(star, ink, x + w - 16 * u, y(18f))
                if (editing && i == selected) {
                    drawRoundRect(Color.White, Offset(x, top), Size(w, height), CornerRadius(6 * u), style = Stroke(2.5f * u))
                    val hh = 30 * u
                    val hy = top + (height - hh) / 2
                    if (i > 0) drawRoundRect(Color.White, Offset(x - 3 * u, hy), Size(7 * u, hh), CornerRadius(3.5f * u))
                    if (i < result.chords.lastIndex) drawRoundRect(Color.White, Offset(x + w - 4 * u, hy), Size(7 * u, hh), CornerRadius(3.5f * u))
                }
            }
        }
        Box(Modifier.offset { IntOffset((width * PLAYHEAD).roundToInt(), 0) }.width(2.dp).fillMaxHeight().background(scheme.onSurface))
    }
}

/** Text whose baseline is at [baseline]. */
private fun DrawScope.text(layout: TextLayoutResult, color: Color, x: Float, baseline: Float) =
    drawText(layout, color, Offset(x, baseline - layout.firstBaseline))
