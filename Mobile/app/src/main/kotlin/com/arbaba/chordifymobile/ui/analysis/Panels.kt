package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ChordSegment
import com.arbaba.chordifymobile.music.MAJOR_CIRCLE
import com.arbaba.chordifymobile.music.MINOR_CIRCLE
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.music.circleIndex
import com.arbaba.chordifymobile.music.functionHue
import com.arbaba.chordifymobile.music.noteToPc
import com.arbaba.chordifymobile.ui.LocalChordifyColors
import com.arbaba.chordifymobile.ui.SectionTitle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// The panels below the timeline (Panels.tsx): the lead sheet, the circle of fifths, and the time
// per chord with the repeating progressions.

/** Four bars a row; the current bar is marked and kept in view, and a tap jumps to a bar. Fills [modifier]. */
@Composable
fun LeadSheet(result: AnalysisResult, notation: Notation, currentBar: Int, modifier: Modifier = Modifier,
              onSeek: (Double) -> Unit) {
    if (result.bars.isEmpty()) {
        Text("No steady beat was found, so there is no bar grid.", modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        return
    }
    val byDisplay = remember(result) { result.chords.associateBy { it.display } }
    val grid = rememberLazyGridState()
    LaunchedEffect(currentBar) {
        if (currentBar >= 0) grid.animateScrollToItem(max(0, (currentBar / 4 - 2) * 4)) // the current row in the middle
    }
    LazyVerticalGrid(GridCells.Fixed(4), state = grid, contentPadding = PaddingValues(14.dp),
                     modifier = modifier.fillMaxSize().testTag("leadSheet")) {
        itemsIndexed(result.bars) { i, bar ->
            val text = bar.chords.joinToString("  ") { if (notation == Notation.ROMAN) byDisplay[it]?.roman ?: it else it }.ifEmpty { "·" }
            val shape = RoundedCornerShape(6.dp)
            val cell = Modifier.padding(2.dp).clip(shape).clickable { onSeek(bar.start + 0.01) }.testTag("bar$i")
            Box(if (i == currentBar) cell.background(MaterialTheme.colorScheme.onSurface)
                else cell.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)) {
                Text(text, Modifier.padding(horizontal = 8.dp, vertical = 10.dp), fontWeight = FontWeight.SemiBold,
                     color = if (i == currentBar) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

private const val C0 = 170f
private const val RO = 132f
private const val RI = 88f

/** The song's chords on the circle of fifths, as Panels.tsx draws it on a 340-unit square. */
@Composable
fun CircleOfFifths(result: AnalysisResult, current: ChordSegment, modifier: Modifier = Modifier) {
    val colors = LocalChordifyColors.current
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val nodes = remember(result) { circleNodes(result) }
    val transitions = remember(result) { circleTransitions(result) }
    val description = circleDescription(result)
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.aspectRatio(1f)
                       .testTag("circle").semantics { contentDescription = description }) {
                val s = size.minDimension / 340f
                fun place(index: Int, radius: Float) =
                    Offset((C0 + radius * sin(index * PI / 6)).toFloat() * s, (C0 - radius * cos(index * PI / 6)).toFloat() * s)
                fun label(text: String, at: Offset, units: Float, color: Color, bold: Boolean = false) {
                    val layout = measurer.measure(text, TextStyle(fontSize = (units * s / density / fontScale).sp,
                                                                   fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
                    drawText(layout, color, Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
                }
                val key = result.key.global
                val tonicIndex = circleIndex(noteToPc(key.tonic), key.mode == "minor")
                // the key's neighbourhood: three places either side of the tonic, both rings
                val from = (tonicIndex - 1.5f) * 30f - 90f
                val wedge = Path().apply {
                    arcTo(Rect(Offset(C0 * s, C0 * s), 158f * s), from, 90f, forceMoveTo = true)
                    arcTo(Rect(Offset(C0 * s, C0 * s), 62f * s), from + 90f, -90f, forceMoveTo = false)
                    close()
                }
                drawPath(wedge, scheme.surfaceVariant)
                drawCircle(colors.grid, RO * s, Offset(C0 * s, C0 * s), style = Stroke(s))
                drawCircle(colors.grid, RI * s, Offset(C0 * s, C0 * s), style = Stroke(s))
                val at = nodes.associate { it.key to place(it.index, if (it.minor) RI else RO) }
                val most = max(1, transitions.values.maxOrNull() ?: 1)
                for ((pair, count) in transitions) {
                    val a = at[pair.first] ?: continue
                    val b = at[pair.second] ?: continue
                    val c = Offset(C0 * s + ((a.x + b.x) / 2 - C0 * s) * 0.25f, C0 * s + ((a.y + b.y) / 2 - C0 * s) * 0.25f)
                    drawPath(Path().apply { moveTo(a.x, a.y); quadraticTo(c.x, c.y, b.x, b.y) }, scheme.onSurfaceVariant,
                             alpha = 0.35f, style = Stroke((1 + 5f * count / most) * s, cap = StrokeCap.Round))
                }
                val currentNode = nodes.firstOrNull { it.key == nodeKey(current) }
                val predicted = current.next.firstOrNull()
                val predictedNode = predicted?.let { p -> nodes.firstOrNull { it.chord.display == p.display } }
                if (currentNode != null && predictedNode != null && currentNode != predictedNode) {
                    drawLine(scheme.onSurface, at.getValue(currentNode.key), at.getValue(predictedNode.key), 2 * s,
                             pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * s, 4 * s)))
                }
                val used = nodes.map { it.label }.toSet()
                MAJOR_CIRCLE.forEachIndexed { i, name -> if (name !in used) label(name, place(i, RO), 12f, colors.muted) }
                MINOR_CIRCLE.forEachIndexed { i, name -> if (name !in used) label(name, place(i, RI), 10f, colors.muted) }
                for (node in nodes) {
                    val r = (8 + 16 * sqrt(node.share)).toFloat() * s
                    val xy = at.getValue(node.key)
                    if (node == currentNode) drawCircle(scheme.onSurface, r + 4 * s, xy, style = Stroke(2 * s))
                    drawCircle(colors.of(functionHue(node.chord.function)), r, xy)
                    drawCircle(scheme.surface, r, xy, style = Stroke(2 * s))
                    label(node.text, xy, 11f, Color.White, bold = true)
                }
            }
    }
}

/** Time per chord, the repeating progressions and the predictability (Panels.tsx ChordShare). */
@Composable
fun ChordShare(result: AnalysisResult, chordsByDisplay: Map<String, ChordSegment>, modifier: Modifier = Modifier) {
    val colors = LocalChordifyColors.current
    val summary = result.summary
    run {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("statsPanel"),
               verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Time per chord")
            val top = summary.timeShare.firstOrNull()?.share ?: 1.0
            for (share in summary.timeShare.take(10)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(share.display, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp))
                    Box(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth((share.share / top).toFloat().coerceIn(0f, 1f)).height(10.dp).clip(RoundedCornerShape(3.dp))
                                .background(colors.of(functionHue(chordsByDisplay[share.display]?.function))))
                    }
                    Text("${percent(share.share)}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SectionTitle("Repeating progressions")
            if (summary.patterns.isEmpty()) Text("No loop repeats three times or more.", style = MaterialTheme.typography.bodySmall)
            for (pattern in summary.patterns) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pattern.roman.joinToString(" – "), fontWeight = FontWeight.Bold)
                        pattern.name?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    Text("×${pattern.count}")
                }
            }
            summary.predictability?.let {
                Text("Predictability: the model gave the real next chord ${percent(it)}% on average.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
