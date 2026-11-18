package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbaba.chordifymobile.music.Shape
import com.arbaba.chordifymobile.ui.LocalChordifyColors

/** A chord box (GuitarDiagram.tsx): drawn on the web's 120 × 150 grid. With a capo, frets are
 *  counted from the capo, as on a capo chart. */
@Composable
fun GuitarDiagram(shape: Shape?, capo: Int, width: Dp = 120.dp) {
    val ink = MaterialTheme.colorScheme.onSurface
    val ink2 = MaterialTheme.colorScheme.onSurfaceVariant
    val axis = MaterialTheme.colorScheme.outline
    val muted = LocalChordifyColors.current.muted
    val text = rememberTextMeasurer()
    Canvas(Modifier.size(width, width * 1.25f).semantics { contentDescription = diagramDescription(shape, capo) }) {
        val unit = size.width / 120f
        fun p(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun label(value: String, x: Float, y: Float, size: Int) {
            val layout = text.measure(value, TextStyle(color = muted, fontSize = (size * unit / density).sp))
            drawText(layout, topLeft = Offset(x * unit - layout.size.width / 2f, y * unit - layout.size.height * 0.8f))
        }
        if (shape == null) {
            label("no diagram", 60f, 80f, 12)
            return@Canvas
        }
        val x0 = 20f
        val y0 = 28f
        val dx = 16f
        val dy = 24f
        val fretted = shape.filter { it > 0 }
        val lowest = fretted.minOrNull() ?: 1
        val base = if ((shape.maxOrNull() ?: 0) > 4) lowest else 1
        if (capo > 0 && base == 1) {
            drawRoundRect(ink2, p(x0 - 7, y0 - 7), Size((dx * 5 + 14) * unit, 7 * unit), CornerRadius(3.5f * unit))
        } else {
            drawRect(ink, p(x0, y0 - 3), Size(dx * 5 * unit, (if (base == 1) 4f else 1f) * unit))
        }
        for (s in 0..5) drawLine(axis, p(x0 + s * dx, y0), p(x0 + s * dx, y0 + dy * 4), unit)
        for (f in 0..4) drawLine(axis, p(x0, y0 + f * dy), p(x0 + dx * 5, y0 + f * dy), unit)
        for ((s, fret) in shape.withIndex()) {
            val x = x0 + s * dx
            val above = if (capo > 0 && base == 1) y0 - 14 else y0 - 10
            when {
                fret < 0 -> label("×", x, above, 11)
                fret == 0 -> drawCircle(ink2, 4 * unit, p(x, above - 4), style = Stroke(1.5f * unit))
                else -> drawCircle(ink, 6 * unit, p(x, y0 + (fret - base + 0.5f) * dy))
            }
        }
        if (base > 1) label("${base}fr", x0 + dx * 5 + 14, y0 + dy * 0.5f + 4, 11)
        label(if (capo > 0) "guitar · capo $capo" else "guitar", 60f, 146f, 12)
    }
}
