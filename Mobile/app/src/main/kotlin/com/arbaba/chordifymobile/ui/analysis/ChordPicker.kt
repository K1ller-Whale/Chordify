package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arbaba.chordifymobile.engine.model.KeyInfo
import com.arbaba.chordifymobile.engine.theory.describe
import com.arbaba.chordifymobile.engine.theory.parseKey
import com.arbaba.chordifymobile.engine.vocab.displayName
import com.arbaba.chordifymobile.engine.vocab.noteToPc
import com.arbaba.chordifymobile.engine.vocab.parse
import com.arbaba.chordifymobile.engine.vocab.parseDisplay
import com.arbaba.chordifymobile.music.FUNCTION_NAMES

private val PICKER_ROOTS = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
private val ROOT_SHOWN = mapOf("C#" to "C♯", "Eb" to "E♭", "F#" to "F♯", "Ab" to "A♭", "Bb" to "B♭")
private val PICKER_TYPES = listOf(
    "major" to "", "minor" to "m", "7" to "7", "maj7" to "maj7", "m7" to "m7", "6" to "6", "m6" to "m6",
    "sus2" to "sus2", "sus4" to "sus4", "dim" to "dim", "aug" to "aug", "9" to "9", "add9" to "add9",
)

/** The Harte label for a picker choice: [root] is "C".."B" in ASCII ("Eb", "F#"), [type] one of
 *  major, minor, 7, maj7, m7, 6, m6, sus2, sus4, dim, aug, 9, add9, and [bass] a root name, or null
 *  (or the root itself) for none. */
fun pickerLabel(root: String, type: String, bass: String?): String {
    val suffix = PICKER_TYPES.first { it.first == type }.second
    val slash = if (bass != null && bass != root) "/$bass" else ""
    return parseDisplay(root + suffix + slash)
}

/** A full-screen dialog to pick a chord. [current] and [suggestions] are shown labels; [onPick] gets a shown Harte label. */
@Composable
fun ChordPicker(current: String, suggestions: List<String>, key: KeyInfo, flats: Boolean,
                onPick: (String) -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ChordPickerContent(current, suggestions, key, flats, onPick, onCancel)
    }
}

/** The picker's content, apart from its dialog window. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChordPickerContent(current: String, suggestions: List<String>, key: KeyInfo, flats: Boolean,
                                onPick: (String) -> Unit, onCancel: () -> Unit) {
    val start = remember(current, flats) { initialChoice(current, flats) }
    var noChord by rememberSaveable(current) { mutableStateOf(start.noChord) }
    var root by rememberSaveable(current) { mutableStateOf(start.root) }
    var type by rememberSaveable(current) { mutableStateOf(start.type) }
    var bass by rememberSaveable(current) { mutableStateOf(start.bass) }

    val label = if (noChord) "N" else pickerLabel(root, type ?: "major", bass)
    val display = displayName(label, flats)
    val preview = remember(label, key) {
        if (label == "N") display else {
            val described = runCatching { describe(label, parseKey("${key.tonic} ${key.mode}")) }.getOrNull()
            val parts = listOfNotNull(described?.roman, described?.function?.let { FUNCTION_NAMES[it] })
            if (parts.isEmpty()) display else display + "\n" + parts.joinToString(" · ")
        }
    }

    Surface(Modifier.fillMaxSize().testTag("chordPicker"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (suggestions.isNotEmpty()) {
                Text("Suggested", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { s ->
                        FilterChip(selected = s == label, onClick = { onPick(s) }, label = { Text(displayName(s, flats)) },
                            modifier = Modifier.testTag("suggestion:$s"))
                    }
                }
            }
            Text("Root", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PICKER_ROOTS.forEach { r ->
                    FilterChip(selected = !noChord && r == root, onClick = { root = r; noChord = false },
                        label = { Text(ROOT_SHOWN[r] ?: r) }, modifier = Modifier.testTag("root:$r"))
                }
            }
            Text("Type", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PICKER_TYPES.forEach { (name, _) ->
                    FilterChip(selected = !noChord && name == (type ?: "major"), onClick = { type = name; noChord = false },
                        label = { Text(name) }, modifier = Modifier.testTag("type:$name"))
                }
            }
            Text("Bass", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = bass == null || bass == root, onClick = { bass = null },
                    label = { Text("none") }, modifier = Modifier.testTag("bass:none"))
                PICKER_ROOTS.forEach { r ->
                    FilterChip(selected = bass == r && r != root, onClick = { bass = r; noChord = false },
                        label = { Text(ROOT_SHOWN[r] ?: r) }, modifier = Modifier.testTag("bass:$r"))
                }
            }
            FilterChip(selected = noChord, onClick = { noChord = true }, label = { Text("No chord (N.C.)") },
                modifier = Modifier.testTag("noChord"))
            Text(preview, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth().testTag("pickerPreview"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onPick(label) }, modifier = Modifier.testTag("pickerUse")) { Text("Use $display") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

private data class Choice(val noChord: Boolean, val root: String, val type: String?, val bass: String?)

/** The root, type and bass of [current], or N.C.; a type the picker lacks is left unselected. */
private fun initialChoice(current: String, flats: Boolean): Choice {
    val chord = runCatching { parse(current) }.getOrNull()
    val pcRoot = chord?.root ?: return Choice(true, "C", "major", null)
    val rootAscii = PICKER_ROOTS.first { noteToPc(it) == pcRoot }
    val suffix = displayName(current, flats).removePrefix(current.substringBefore(':').substringBefore('/')).substringBefore('/')
    val type = PICKER_TYPES.firstOrNull { it.second == suffix }?.first
    val bassPc = chord.bass
    val bass = if (bassPc != null && bassPc != pcRoot) {
        PICKER_ROOTS.first { noteToPc(it) == bassPc }
    } else null
    return Choice(false, rootAscii, type, bass)
}
