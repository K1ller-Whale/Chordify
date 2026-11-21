package com.arbaba.chordifymobile.songwriter

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arbaba.chordifymobile.ChordifyApp
import com.arbaba.chordifymobile.engine.model.ProgressionResponse
import com.arbaba.chordifymobile.ui.AdaptiveLayout
import com.arbaba.chordifymobile.ui.LocalChordifyColors
import com.arbaba.chordifymobile.ui.ScreenTopBar
import com.arbaba.chordifymobile.ui.SectionTitle
import com.arbaba.chordifymobile.ui.TwoPanes
import com.arbaba.chordifymobile.ui.analysis.percent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The Songwriter (spec §6.2, chordify-frontend/src/pages/Songwriter.tsx): type a progression and
// see what usually follows, from the engine's nextChords (POST /api/v2/progressions/next).

/** The web's key choices; "" detects the key from the chords. */
val KEYS = listOf("", "C", "G", "D", "A", "E", "B", "F#", "Db", "Ab", "Eb", "Bb", "F", "Am", "Em", "Bm", "F#m", "C#m", "Dm", "Gm", "Cm")

/** How many suggestions the Songwriter asks for, as the web does. */
const val SUGGESTIONS = 5

/** "F G" or "F, G | Am" as chord names. */
fun splitChords(draft: String): List<String> = draft.split(Regex("[\\s,|]+")).filter { it.isNotEmpty() }

/** "Assuming C major (87% sure)" for an estimated key, "In G major" for a chosen one. */
fun keyLine(response: ProgressionResponse): String {
    val key = response.keyUsed
    return (if (key.estimated) "Assuming" else "In") + " ${key.tonic} ${key.mode}" +
        if (key.estimated) " (${percent(key.confidence)}% sure)" else ""
}

class SongwriterViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = (application as ChordifyApp).container.engine
    val chords = MutableStateFlow(listOf("C", "G", "Am"))
    val draft = MutableStateFlow("")
    val key = MutableStateFlow("")
    val answer = MutableStateFlow<ProgressionResponse?>(null)
    val error = MutableStateFlow<String?>(null)

    fun update(list: List<String>) {
        chords.value = list
    }

    fun add() {
        val names = splitChords(draft.value)
        if (names.isNotEmpty()) update(chords.value + names)
        draft.value = ""
    }

    fun setKey(value: String) {
        key.value = value
    }

    private var asking: Job? = null

    /** Asks the engine for suggestions; a newer ask cancels the one still running, so the latest wins. */
    fun ask(): Job {
        asking?.cancel()
        return viewModelScope.launch { suggest() }.also { asking = it }
    }

    private suspend fun suggest() {
        error.value = null
        val list = chords.value
        if (list.isEmpty()) {
            answer.value = null
            return
        }
        try {
            val engine = engine.concurrent()
            answer.value = withContext(Dispatchers.Default) { engine.nextChords(list, key.value.ifEmpty { null }, SUGGESTIONS) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            answer.value = null
            error.value = failure.message
        }
    }
}

/** The Songwriter tab. */
@Composable
fun SongwriterScreen(onBack: () -> Unit = {}, model: SongwriterViewModel = viewModel()) {
    val chords by model.chords.collectAsStateWithLifecycle()
    val draft by model.draft.collectAsStateWithLifecycle()
    val key by model.key.collectAsStateWithLifecycle()
    val answer by model.answer.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    SongwriterContent(chords, draft, key, answer, error,
                      onRemove = { i -> model.update(chords.filterIndexed { j, _ -> j != i }) },
                      onDraft = { model.draft.value = it }, onAdd = model::add, onKey = model::setKey,
                      onSuggest = { model.ask() }, onPick = { model.update(chords + it) }, onBack = onBack)
}

/** The Songwriter without its view model, so tests can show it in any state. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SongwriterContent(chords: List<String>, draft: String, key: String, answer: ProgressionResponse?, error: String?,
                      onRemove: (Int) -> Unit, onDraft: (String) -> Unit, onAdd: () -> Unit, onKey: (String) -> Unit,
                      onSuggest: () -> Unit, onPick: (String) -> Unit, onBack: () -> Unit = {}) {
    val colors = LocalChordifyColors.current
    var choosingKey by remember { mutableStateOf(false) }
    LaunchedEffect(chords, key) { onSuggest() }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Songwriter", onBack) {
            Box(Modifier.padding(end = 8.dp)) {
                AssistChip(onClick = { choosingKey = true }, label = { Text("Key: " + key.ifEmpty { "from the chords" } + " ▾") },
                           modifier = Modifier.testTag("key"))
                DropdownMenu(expanded = choosingKey, onDismissRequest = { choosingKey = false }) {
                    for (choice in KEYS) {
                        DropdownMenuItem(text = { Text(choice.ifEmpty { "From the chords" }) },
                                         onClick = { onKey(choice); choosingKey = false })
                    }
                }
            }
        }
        AdaptiveLayout(Modifier.weight(1f).fillMaxWidth()) { landscape ->
            TwoPanes(landscape, first = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle("Progression")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        chords.forEachIndexed { i, chord ->
                            InputChip(selected = false, onClick = { onRemove(i) }, label = { Text("$chord ×") },
                                      modifier = Modifier.semantics { contentDescription = "Remove $chord" })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(draft, onDraft, label = { Text("Add chords, e.g. F G") }, singleLine = true,
                                          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                          keyboardActions = KeyboardActions(onDone = { onAdd() }),
                                          modifier = Modifier.weight(1f).testTag("draft"))
                        Button(onClick = onAdd) { Text("Add") }
                    }
                    Text("Type chords as you write them (C, Am7, F/C, G7sus4); tap a chip to remove it.",
                         style = MaterialTheme.typography.bodySmall, color = colors.muted)
                }
            }, second = {
                Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("What could come next")
                    if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                    if (answer == null) {
                        Text("Suggestions appear here.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(keyLine(answer), style = MaterialTheme.typography.bodySmall)
                        for (prediction in answer.predictions) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { onPick(prediction.display) }, modifier = Modifier.width(88.dp)
                                                   .semantics { contentDescription = "Add ${prediction.display}" }) {
                                        Text(prediction.display, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Text(prediction.roman.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                                         fontWeight = FontWeight.SemiBold, modifier = Modifier.width(44.dp))
                                    Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                        Box(Modifier.fillMaxWidth(prediction.p.toFloat().coerceIn(0f, 1f)).height(8.dp).background(colors.tonic))
                                    }
                                    Text("${percent(prediction.p)}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(prediction.reason, style = MaterialTheme.typography.bodySmall, color = colors.muted,
                                     modifier = Modifier.padding(start = 96.dp))
                            }
                        }
                    }
                }
            })
        }
    }
}
