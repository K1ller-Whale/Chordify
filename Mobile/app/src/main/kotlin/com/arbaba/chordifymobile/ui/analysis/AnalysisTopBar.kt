package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.arbaba.chordifymobile.data.ExportFormat
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.music.CapoSuggestion
import com.arbaba.chordifymobile.music.MAX_CAPO
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.ui.ScreenTopBar

/** Below this bar width the key, tempo and meter line is left out. */
private val KEY_LINE_MIN_WIDTH = 480.dp

/** The analysis screen's top bar: Back, the title, the capo chip and the more menu (notation, export, pick the file). */
@Composable
fun AnalysisTopBar(title: String, analysed: AnalysisResult, capo: Int, suggestion: CapoSuggestion,
                   notation: Notation, onBack: () -> Unit, onCapo: (Int) -> Unit,
                   onNotation: (Notation) -> Unit, onExport: (ExportFormat) -> Unit,
                   onPickFile: () -> Unit, modifier: Modifier = Modifier) {
    var choosingCapo by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier) {
        val showFacts = maxWidth >= KEY_LINE_MIN_WIDTH
        ScreenTopBar(title, onBack) {
            if (showFacts) {
                Text(songFacts(analysed), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            }
            Box {
                AssistChip(onClick = { choosingCapo = true }, label = { Text(capoLabel(capo) + " ▾") },
                    modifier = Modifier.testTag("capo"))
                DropdownMenu(expanded = choosingCapo, onDismissRequest = { choosingCapo = false }) {
                    for (fret in 0..MAX_CAPO) {
                        DropdownMenuItem(text = { Text(capoMenuLabel(fret, suggestion)) },
                            onClick = { choosingCapo = false; onCapo(fret) })
                    }
                }
            }
            Box {
                IconButton(onClick = { more = true }, modifier = Modifier.testTag("more")) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(
                        text = { Text(if (notation == Notation.LETTERS) "Show Roman numerals" else "Show letters") },
                        onClick = {
                            more = false
                            onNotation(if (notation == Notation.LETTERS) Notation.ROMAN else Notation.LETTERS)
                        })
                    DropdownMenuItem(text = { Text("Export chords (.lab)") }, onClick = { more = false; onExport(ExportFormat.LAB) })
                    DropdownMenuItem(text = { Text("Export analysis (.json)") }, onClick = { more = false; onExport(ExportFormat.JSON) })
                    DropdownMenuItem(text = { Text("Pick the audio file") }, onClick = { more = false; onPickFile() })
                }
            }
        }
    }
}
