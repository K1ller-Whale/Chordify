package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arbaba.chordifymobile.edit.missingNote
import com.arbaba.chordifymobile.edit.strum
import com.arbaba.chordifymobile.engine.vocab.displayName

private val STRINGS = listOf("E", "A", "D", "G", "B", "e")
private const val WINDOW_FRETS = 4
private const val MAX_START = 12
private val CELL_WIDTH = 44.dp
private val CELL_HEIGHT = 40.dp

/** A full-screen dialog to edit a chord's guitar shape. [label] is the shape label; onSave(null) goes back to the default shape. */
@Composable
fun ShapeEditor(label: String, shape: List<Int>?, defaultShape: List<Int>?, capo: Int,
                onSave: (List<Int>?) -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ShapeEditorContent(label, shape, defaultShape, capo, onSave, onCancel)
    }
}

/** The dialog's content, separate from the window. [onStrum] plays the shape. */
@Composable
internal fun ShapeEditorContent(label: String, shape: List<Int>?, defaultShape: List<Int>?, capo: Int,
                                onSave: (List<Int>?) -> Unit, onCancel: () -> Unit,
                                onStrum: (List<Int>, Int) -> Unit = ::strum) {
    var frets by remember { mutableStateOf(shape ?: defaultShape ?: List(6) { -1 }) }
    var start by remember { mutableIntStateOf((frets.filter { it > 0 }.minOrNull() ?: 1).coerceIn(1, MAX_START)) }
    val warning = missingNote(frets, label)
    fun set(string: Int, fret: Int) { frets = frets.toMutableList().also { it[string] = fret } }

    Surface(Modifier.fillMaxSize().testTag("shapeEditor"), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
            val board: @Composable () -> Unit = { Fretboard(frets, start, ::set) }
            val controls: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Edit shape: ${displayName(label)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (capo > 0) "Frets are counted from the capo (fret $capo)." else "Tap a string and fret.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ if (start < MAX_START) start++ }, enabled = start < MAX_START) { Text("Higher up the neck") }
                        OutlinedButton({ if (start > 1) start-- }, enabled = start > 1) { Text("Lower down the neck") }
                    }
                    Text("Starting fret: $start", style = MaterialTheme.typography.bodyMedium)
                    if (warning != null) {
                        Text(warning, Modifier.testTag("shapeWarning"), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ onStrum(frets, capo) }) { Text("Strum it") }
                        OutlinedButton({ onSave(null) }) { Text("Default shape") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ onSave(frets) }) { Text("Save") }
                        TextButton(onCancel) { Text("Cancel") }
                    }
                }
            }
            if (maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { board() }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { controls() }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    board()
                    controls()
                }
            }
        }
    }
}

/** The nut row (open / muted) and [WINDOW_FRETS] fret rows from [start]; strings run E A D G B e left to right. */
@Composable
private fun Fretboard(frets: List<Int>, start: Int, set: (Int, Int) -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val axis = MaterialTheme.colorScheme.outline
    val dot = MaterialTheme.colorScheme.primary
    Column {
        Row {
            Spacer(Modifier.width(32.dp))
            for ((s, name) in STRINGS.withIndex()) {
                val fret = frets[s]
                val state = when {
                    fret < 0 -> "muted"
                    fret == 0 -> "open"
                    else -> "fret $fret"
                }
                Box(Modifier.size(CELL_WIDTH, CELL_HEIGHT)
                    .clickable { set(s, if (fret == 0) -1 else 0) }
                    .semantics(mergeDescendants = true) { contentDescription = "String $name: $state" },
                    contentAlignment = Alignment.Center) {
                    Text(when { fret < 0 -> "×"; fret == 0 -> "○"; else -> "" }, color = ink,
                        style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        for (row in 0 until WINDOW_FRETS) {
            val fret = start + row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$fret", Modifier.width(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium)
                for ((s, name) in STRINGS.withIndex()) {
                    val on = frets[s] == fret
                    Box(Modifier.size(CELL_WIDTH, CELL_HEIGHT)
                        .drawBehind {
                            drawLine(axis, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 2f)
                            drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), 2f)
                        }
                        .clickable { set(s, if (on) 0 else fret) }
                        .semantics(mergeDescendants = true) { contentDescription = "String $name, fret $fret" },
                        contentAlignment = Alignment.Center) {
                        if (on) Box(Modifier.size(26.dp).background(dot, CircleShape).border(1.dp, ink, CircleShape))
                    }
                }
            }
        }
    }
}
