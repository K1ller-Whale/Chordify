package com.arbaba.chordifymobile.ui.analysis

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.data.ExportFormat
import com.arbaba.chordifymobile.data.Status
import com.arbaba.chordifymobile.edit.DISCARD_CONFIRM
import com.arbaba.chordifymobile.edit.EDITS_UNREADABLE
import com.arbaba.chordifymobile.edit.EditActions
import com.arbaba.chordifymobile.edit.EditUiState
import com.arbaba.chordifymobile.edit.NoEditActions
import com.arbaba.chordifymobile.edit.RESTORE_CONFIRM
import com.arbaba.chordifymobile.edit.SAVE_FAILED
import com.arbaba.chordifymobile.edit.shapeLabelOf
import com.arbaba.chordifymobile.edit.soundingLabel
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.KeyInfo
import com.arbaba.chordifymobile.music.guitarShape
import com.arbaba.chordifymobile.music.shapeKey
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.music.chordIndexAt
import com.arbaba.chordifymobile.music.isNoChord
import com.arbaba.chordifymobile.music.suggestCapo
import com.arbaba.chordifymobile.music.withCapo
import com.arbaba.chordifymobile.playback.PlaybackClock
import com.arbaba.chordifymobile.ui.AdaptiveLayout
import com.arbaba.chordifymobile.ui.stageLabel

/** The analysis screen (M7 spec §3), opened from the Library: the analysing, failed and cancelled
 *  states, or the completed analysis ([AnalysisContent]). */
@Composable
fun AnalysisScreen(id: String, onBack: () -> Unit = {}) {
    val application = LocalContext.current.applicationContext as Application
    val model: AnalysisViewModel = viewModel(key = id) { AnalysisViewModel(application, id) }
    val row by model.analysis.collectAsStateWithLifecycle()
    val result by model.result.collectAsStateWithLifecycle()
    val notation by model.notation.collectAsStateWithLifecycle()
    val audio by model.audio.collectAsStateWithLifecycle()
    val mismatch by model.mismatch.collectAsStateWithLifecycle()
    val peaks by model.peaks.collectAsStateWithLifecycle()
    val hasEdits by model.hasEdits.collectAsStateWithLifecycle()
    val savedShapes by model.savedShapes.collectAsStateWithLifecycle()
    val editsDamaged by model.editsDamaged.collectAsStateWithLifecycle()
    val edit by model.edit.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.attach(uri) }
    val analysis = row
    val analysed = result
    val clock = model.clock
    if (analysis == null) return
    if (analysis.status != Status.COMPLETED || analysed == null || clock == null) {
        AnalysisStatus(analysis, onRetry = { model.retry() })
        return
    }
    AnalysisContent(
        title = analysis.displayName,
        analysed = analysed,
        clock = clock,
        notation = notation,
        capo = analysis.capo,
        audio = audio,
        mismatch = mismatch,
        onNotation = { model.notation.value = it },
        onCapo = { model.setCapo(it) },
        onPickFile = { pick.launch(arrayOf("audio/*")) },
        peaks = peaks,
        onExport = { format -> model.export(format) { context.startActivity(it) } },
        onBack = onBack,
        shapes = savedShapes,
        hasEdits = hasEdits,
        editsDamaged = editsDamaged,
        edit = edit,
        editActions = model,
    )
}

/** The screen of an analysis that has no result to show yet, or none at all. */
@Composable
fun AnalysisStatus(analysis: Analysis, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(analysis.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        when (analysis.status) {
            Status.FAILED -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Could not analyse this audio", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                    Text(analysis.errorMessage.orEmpty())
                    Text("Error code: ${analysis.errorCode}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
            }
            Status.CANCELLED -> {
                Text("This analysis was cancelled.")
                TextButton(onClick = onRetry) { Text("Analyse") }
            }
            else -> {
                Text(stageLabel(analysis.stage))
                LinearProgressIndicator(progress = { analysis.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** A completed analysis, without its view model, so tests can show it in any state. In a wide
 *  space the stage and the chord strip sit beside one tabbed panel; in a tall one they stack. The
 *  top bar keeps the song's real key; everything below shows the shapes to play with the capo on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisContent(
    title: String,
    analysed: AnalysisResult,
    clock: PlaybackClock,
    notation: Notation,
    capo: Int,
    audio: AudioFile,
    mismatch: Boolean,
    onNotation: (Notation) -> Unit,
    onCapo: (Int) -> Unit,
    onPickFile: () -> Unit,
    peaks: FloatArray? = null,
    onExport: (ExportFormat) -> Unit = {},
    onBack: () -> Unit = {},
    shapes: Map<String, List<Int>> = emptyMap(),
    hasEdits: Boolean = false,
    editsDamaged: Boolean = false,
    edit: EditUiState? = null,
    editActions: EditActions = NoEditActions,
) {
    val preview = edit?.preview
    val result = remember(analysed, capo, preview) { withCapo(preview ?: analysed, capo) }
    val shown = if (edit != null) edit.shapes else shapes
    val flats = remember(analysed, capo) { shapeKey(analysed.key.global.tonic, analysed.key.global.mode, capo).flats }
    var dialog by remember { mutableStateOf<EditDialog?>(null) }
    val cancelEdit = { if (edit?.dirty == true) dialog = EditDialog.DISCARD else editActions.cancelEditing() }
    BackHandler(enabled = edit != null) { cancelEdit() }
    val suggestion = remember(analysed) { suggestCapo(analysed.chords) }
    // The time is read by small scopes only (the Chord panel's countdown, the strip, the seek bar
    // and the time text), so the rest of the screen recomposes when the chord changes, not every
    // frame (spec §6.3).
    val time: State<Double> = clock.time.collectAsState()
    val playing by clock.playing.collectAsState()
    val rate by clock.rate.collectAsState()
    val starts = remember(result) { result.chords.map { it.start } }
    val index by remember(starts) { derivedStateOf { chordIndexAt(starts, time.value) } }
    // While editing the stage shows the selected chord, the one the panel and the editors act on.
    val selected = edit?.selected?.takeIf { it in result.chords.indices }
    val shownIndex = selected ?: index
    val chord = result.chords[shownIndex]
    val sounding = (preview ?: analysed).chords.getOrNull(shownIndex)?.display
    val chordsByDisplay = remember(result) { result.chords.filter { !isNoChord(it.label) }.associateBy { it.display } }
    val surprising = remember(result) { surprisingChords(result.chords) }
    val barStarts = remember(result) { result.bars.map { it.start } }
    val bar by remember(barStarts) { derivedStateOf { barIndexAt(barStarts, time.value) } }
    var tab by rememberSaveable { mutableStateOf(PanelTab.CHORD) }
    var stageWidthPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val visibleSeconds = with(density) { stageWidthPx.toDp().value }.toDouble() / PIXELS_PER_SECOND.toDouble()
    val focus = remember { FocusRequester() }
    val view = LocalView.current

    LaunchedEffect(clock, playing) {
        while (playing) withFrameNanos { clock.tick() }
    }
    DisposableEffect(playing) { // the screen stays on while playing (spec §6.2)
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(focus) { focus.requestFocus() } // for the keyboard shortcuts

    // The web's shortcuts: Space plays or pauses, the arrows jump between chords, R toggles Roman numerals.
    val keys = Modifier.focusRequester(focus).focusable().onPreviewKeyEvent { event ->
        if (edit != null || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.Spacebar -> clock.toggle()
            Key.DirectionRight -> clock.seek(chordStep(starts, clock.time.value, 1))
            Key.DirectionLeft -> clock.seek(chordStep(starts, clock.time.value, -1))
            Key.R -> onNotation(if (notation == Notation.ROMAN) Notation.LETTERS else Notation.ROMAN)
            else -> return@onPreviewKeyEvent false
        }
        true
    }

    // The no-audio banner (when there is one) above the stage, so it hides none of it; then the chord strip.
    val stageColumn: @Composable ColumnScope.() -> Unit = {
        if (editsDamaged) EditsDamagedBanner(Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp))
        NoAudioBanner(audio, mismatch, onPickFile, Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp))
        Stage(chord, notation, capo, sounding, onSwipe = { clock.seek(chordStep(starts, clock.time.value, it)) },
              modifier = Modifier.weight(1f).fillMaxWidth(), shape = shown[chord.label] ?: guitarShape(chord))
        ChordStrip(result, time, index, notation, peaks, surprising, onSeek = { clock.seek(it) },
                   editing = edit != null, selected = edit?.selected, onSelect = editActions::select,
                   onMoveEdge = editActions::moveEdge)
    }
    // The tab row over the selected panel. Compact tabs (labelLarge, 4 dp padding instead of
    // Material's 16 dp) so that all five labels fit in the 40% of a landscape phone.
    val editColumn: @Composable ColumnScope.() -> Unit = {
        edit?.saveError?.let {
            Text(SAVE_FAILED + it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp).testTag("saveError"))
        }
        val picked = selected
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (picked == null) {
                Text("Tap a chord in the strip to change it.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp).testTag("editHint"))
            } else {
                EditPanel(result.chords[picked], picked, result.chords.size, notation, result.beats, { time.value },
                    onSetStart = { editActions.setStart(picked, it) }, onSetEnd = { editActions.setEnd(picked, it) },
                    onSplit = { editActions.split(picked, time.value) }, onMergeWithNext = { editActions.mergeWithNext(picked) },
                    onChangeChord = { dialog = EditDialog.PICKER }, onEditShape = { dialog = EditDialog.SHAPE },
                    modifier = Modifier.fillMaxSize())
            }
        }
    }
    val tabColumn: @Composable ColumnScope.() -> Unit = {
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            for (entry in PanelTab.entries) {
                Tab(selected = tab == entry, onClick = { tab = entry }, modifier = Modifier.height(48.dp).testTag("tab-${entry.name}")) {
                    Text(entry.label, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val fill = Modifier.fillMaxSize()
            when (tab) {
                PanelTab.CHORD -> ChordPanel(chord, result, { time.value }, notation, fill)
                PanelTab.SHEET -> LeadSheet(result, notation, bar, fill) { clock.seek(it) }
                PanelTab.CIRCLE -> CircleOfFifths(result, chord, fill)
                PanelTab.STATS -> ChordShare(result, chordsByDisplay, fill)
                PanelTab.SONG -> SongPanel(analysed, result, capo, suggestion, fill)
            }
        }
    }
    val panelColumn = if (edit != null) editColumn else tabColumn

    Column(Modifier.fillMaxSize().then(keys).testTag("analysis")) {
        AnalysisTopBar(title, analysed, capo, suggestion, notation, onBack, onCapo, onNotation, onExport, onPickFile,
            editing = edit != null, canUndo = edit?.canUndo == true, canRedo = edit?.canRedo == true, hasEdits = hasEdits,
            onEdit = editActions::startEditing, onUndo = editActions::undo, onRedo = editActions::redo,
            onCancelEdit = { cancelEdit() }, onDone = editActions::done, onRestore = { dialog = EditDialog.RESTORE })
        AdaptiveLayout(Modifier.weight(1f).fillMaxWidth()) { landscape ->
            val stageArea = Modifier.onSizeChanged { stageWidthPx = it.width.toFloat() }.testTag("stageArea")
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Column(stageArea.weight(0.6f).fillMaxHeight(), content = stageColumn)
                    Column(Modifier.weight(0.4f).fillMaxHeight().testTag("panelArea"), content = panelColumn)
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // the stage takes 40% of the height, the strip comes under it
                    val stageHeight = maxHeight * 0.4f + STRIP_HEIGHT
                    Column(Modifier.fillMaxSize()) {
                        Column(stageArea.fillMaxWidth().height(stageHeight), content = stageColumn)
                        Column(Modifier.weight(1f).fillMaxWidth().testTag("panelArea"), content = panelColumn)
                    }
                }
            }
        }
        TransportBar(result, time, playing, rate, visibleSeconds, onToggle = clock::toggle, onSeek = clock::seek,
                     onRate = clock::setRate, looping = edit?.looping, onLoop = editActions::toggleLoop)
    }

    // A dialog left open for a chord that is gone, or for an edit that ended, must not reopen later.
    LaunchedEffect(edit != null, selected) {
        val d = dialog
        if ((edit == null && d != null && d != EditDialog.RESTORE) ||
            (selected == null && (d == EditDialog.PICKER || d == EditDialog.SHAPE))) dialog = null
    }
    EditDialogs(dialog, { dialog = null }, selected, result, capo, flats, shown, preview, editActions)
}

/** The picker's and the shape editor's slots, so tests can replace the dialogs. */
internal typealias PickerSlot = @Composable (String, List<String>, KeyInfo, Boolean, (String) -> Unit, () -> Unit) -> Unit
internal typealias ShapeEditorSlot = @Composable (String, List<Int>?, List<Int>?, Int, (List<Int>?) -> Unit, () -> Unit) -> Unit

/** The screen's open dialog, if any. [result] is the shown (capo) result and [selected] an index into it. */
@Composable
internal fun EditDialogs(dialog: EditDialog?, onClose: () -> Unit, selected: Int?, result: AnalysisResult, capo: Int,
                         flats: Boolean, shapes: Map<String, List<Int>>, preview: AnalysisResult?, editActions: EditActions,
                         picker: PickerSlot = { a, b, c, d, e, f -> ChordPicker(a, b, c, d, e, f) },
                         shapeEditor: ShapeEditorSlot = { a, b, c, d, e, f -> ShapeEditor(a, b, c, d, e, f) }) {
    when (dialog) {
        EditDialog.PICKER -> if (selected != null) {
            val label = result.chords[selected].label
            var suggestions by remember(selected, preview) { mutableStateOf(emptyList<String>()) }
            LaunchedEffect(selected, preview) {
                suggestions = editActions.suggestions(selected).map { shapeLabelOf(it, capo, flats) }
            }
            picker(label, suggestions, result.key.global, flats,
                { onClose(); editActions.rename(selected, pickToRename(it, capo, flats)) }, onClose)
        }
        EditDialog.SHAPE -> if (selected != null) {
            val chosen = result.chords[selected]
            shapeEditor(chosen.label, shapes[chosen.label], guitarShape(chosen), capo,
                { onClose(); editActions.setShape(chosen.label, it) }, onClose)
        }
        EditDialog.DISCARD -> ConfirmDialog(DISCARD_CONFIRM, "Discard", "Keep editing", "discardDialog",
            onConfirm = { onClose(); editActions.cancelEditing() }, onDismiss = onClose)
        EditDialog.RESTORE -> ConfirmDialog(RESTORE_CONFIRM, "Restore", "Keep my chords", "restoreDialog",
            onConfirm = { onClose(); editActions.restore() }, onDismiss = onClose)
        null -> {}
    }
}

/** The dialogs the analysis screen can have open. */
internal enum class EditDialog { PICKER, SHAPE, DISCARD, RESTORE }

/** The sounding label for a [picked] shown label: the picker shows the shapes to play with the capo on. */
internal fun pickToRename(picked: String, capo: Int, flats: Boolean): String = soundingLabel(picked, capo, flats)

@Composable
private fun ConfirmDialog(text: String, confirm: String, dismiss: String, tag: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag(tag), text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss) } })
}

/** The slim banner shown when the song's saved edits could not be read. */
@Composable
private fun EditsDamagedBanner(modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth().testTag("editsDamaged")) {
        Text(EDITS_UNREADABLE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

/** The chord strip's height, which the portrait stage's 40% does not include. */
private val STRIP_HEIGHT = 72.dp
