package com.arbaba.chordifymobile.ui.analysis

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.data.ExportFormat
import com.arbaba.chordifymobile.data.Status
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.music.MAX_CAPO
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.music.chordIndexAt
import com.arbaba.chordifymobile.music.formatTime
import com.arbaba.chordifymobile.music.isNoChord
import com.arbaba.chordifymobile.music.suggestCapo
import com.arbaba.chordifymobile.music.withCapo
import com.arbaba.chordifymobile.playback.PlaybackClock
import com.arbaba.chordifymobile.ui.stageLabel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The analysis screen (spec §6.2), opened from the Library. */
@Composable
fun AnalysisScreen(id: String) {
    val application = LocalContext.current.applicationContext as Application
    val model: AnalysisViewModel = viewModel(key = id) { AnalysisViewModel(application, id) }
    val row by model.analysis.collectAsStateWithLifecycle()
    val result by model.result.collectAsStateWithLifecycle()
    val notation by model.notation.collectAsStateWithLifecycle()
    val audio by model.audio.collectAsStateWithLifecycle()
    val mismatch by model.mismatch.collectAsStateWithLifecycle()
    val peaks by model.peaks.collectAsStateWithLifecycle()
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

/** A completed analysis, without its view model, so tests can show it in any state. The header
 *  keeps the song's real key; everything below shows the shapes to play with the capo on. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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
) {
    val result = remember(analysed, capo) { withCapo(analysed, capo) }
    val suggestion = remember(analysed) { suggestCapo(analysed.chords) }
    // The time is read by small scopes only (the countdown, the progress bar, the time text), so
    // the rest of the screen recomposes when the chord changes, not every frame (spec §6.3).
    val time: State<Double> = clock.time.collectAsState()
    val playing by clock.playing.collectAsState()
    val starts = remember(result) { result.chords.map { it.start } }
    val index by remember(starts) { derivedStateOf { chordIndexAt(starts, time.value) } }
    val chord = result.chords[index]
    val chordsByDisplay = remember(result) { result.chords.filter { !isNoChord(it.label) }.associateBy { it.display } }
    val surprising = remember(result) { surprisingChords(result.chords) }
    val barStarts = remember(result) { result.bars.map { it.start } }
    val bar by remember(barStarts) { derivedStateOf { barIndexAt(barStarts, time.value) } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
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

    // A swipe on Now playing goes to the next chord (to the left) or the previous one (to the right).
    val swipe = Modifier.testTag("nowPlaying").pointerInput(starts) {
        var moved = 0f
        detectHorizontalDragGestures(onDragStart = { moved = 0f }, onDragEnd = {
            if (abs(moved) > 48.dp.toPx()) clock.seek(chordStep(starts, clock.time.value, if (moved < 0) 1 else -1))
        }) { _, dx -> moved += dx }
    }
    // The web's shortcuts: Space plays or pauses, the arrows jump between chords, R toggles Roman numerals.
    val keys = Modifier.focusRequester(focus).focusable().onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.Spacebar -> clock.toggle()
            Key.DirectionRight -> clock.seek(chordStep(starts, clock.time.value, 1))
            Key.DirectionLeft -> clock.seek(chordStep(starts, clock.time.value, -1))
            Key.R -> onNotation(if (notation == Notation.ROMAN) Notation.LETTERS else Notation.ROMAN)
            else -> return@onPreviewKeyEvent false
        }
        true
    }

    Column(Modifier.fillMaxSize().then(keys).testTag("analysis")) {
        BoxWithConstraints(Modifier.weight(1f)) {
            // the web's two columns: Now playing and What comes next side by side on a wide screen
            val wide = maxWidth >= 600.dp && maxWidth > maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Header(analysed, result, notation, capo, onNotation, onCapo, onExport)
                Notices(analysed, capo, suggestion.let { suggestionNotice(it, capo) }, suggestion.capo, audio, mismatch, onCapo, onPickFile)
                val sounding = analysed.chords.getOrNull(index)?.display
                val next = actualNext(result.chords, chord)
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NowPlayingCard(chord, result, { time.value }, notation, capo, sounding, swipe.weight(1f))
                        NextChordCard(chord, next, chordsByDisplay, notation, Modifier.weight(1f).testTag("whatComesNext"))
                    }
                } else {
                    NowPlayingCard(chord, result, { time.value }, notation, capo, sounding, swipe)
                    NextChordCard(chord, next, chordsByDisplay, notation, Modifier.testTag("whatComesNext"))
                }
                Timeline(result, clock, time, index, notation, peaks, surprising)
                PrimaryTabRow(selectedTabIndex = tab) {
                    for ((i, name) in listOf("Lead sheet", "Circle of fifths", "Stats").withIndex()) {
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(name) })
                    }
                }
                when (tab) {
                    0 -> LeadSheet(result, notation, bar) { clock.seek(it) }
                    1 -> CircleOfFifths(result, chord)
                    else -> ChordShare(result, chordsByDisplay)
                }
                Text("Models: " + result.models.entries.joinToString(" · ") { "${it.key} ${it.value}" } +
                     ". With a keyboard, Space plays or pauses, ← and → jump between chords, and R toggles Roman numerals.",
                     style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
            }
        }
        Transport({ time.value }, result.source.duration, playing, clock)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(analysed: AnalysisResult, result: AnalysisResult, notation: Notation, capo: Int,
                   onNotation: (Notation) -> Unit, onCapo: (Int) -> Unit, onExport: (ExportFormat) -> Unit) {
    val key = analysed.key.global
    var choosing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AssistChip(onClick = {}, label = { Text("Key ${key.tonic} ${key.mode}") })
        result.tempo.bpm?.let { AssistChip(onClick = {}, label = { Text("${it.roundToInt()} BPM") }) }
        AssistChip(onClick = {}, label = { Text(result.tempo.meter) })
        AssistChip(onClick = {}, label = { Text("${result.summary.uniqueChords} chords") })
        Box {
            AssistChip(onClick = { choosing = true }, label = { Text(if (capo == 0) "Capo: none" else "Capo: fret $capo") },
                       modifier = Modifier.testTag("capo"))
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                for (fret in 0..MAX_CAPO) {
                    DropdownMenuItem(text = { Text(if (fret == 0) "none" else "fret $fret") },
                                     onClick = { onCapo(fret); choosing = false })
                }
            }
        }
        Box {
            AssistChip(onClick = { exporting = true }, label = { Text("Export") }, modifier = Modifier.testTag("export"))
            DropdownMenu(expanded = exporting, onDismissRequest = { exporting = false }) {
                DropdownMenuItem(text = { Text("Chords as .lab") }, onClick = { onExport(ExportFormat.LAB); exporting = false })
                DropdownMenuItem(text = { Text("Whole analysis as .json") }, onClick = { onExport(ExportFormat.JSON); exporting = false })
            }
        }
    }
    SingleChoiceSegmentedButtonRow {
        for ((i, choice) in Notation.entries.withIndex()) {
            SegmentedButton(selected = notation == choice, onClick = { onNotation(choice) },
                            shape = SegmentedButtonDefaults.itemShape(i, Notation.entries.size)) {
                Text(if (choice == Notation.LETTERS) "Letters" else "Roman")
            }
        }
    }
}

@Composable
private fun Notices(analysed: AnalysisResult, capo: Int, suggestion: String?, suggestedCapo: Int, audio: AudioFile,
                    mismatch: Boolean, onCapo: (Int) -> Unit, onPickFile: () -> Unit) {
    val capoNotice = capoNotice(analysed, capo)
    if (capoNotice != null || suggestion != null) {
        Notice {
            capoNotice?.let { Text(it) }
            if (suggestion != null) {
                Text(suggestion)
                TextButton(onClick = { onCapo(suggestedCapo) }) { Text("Use capo $suggestedCapo") }
            }
        }
    }
    if (audio == AudioFile.MISSING) {
        Notice {
            Text(FILE_MISSING)
            Button(onClick = onPickFile) { Text("Pick the file") }
        }
    }
    if (mismatch) Notice { Text(FILE_MISMATCH, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun Notice(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun Transport(time: () -> Double, duration: Double, playing: Boolean, clock: PlaybackClock) {
    val rate by clock.rate.collectAsState()
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { clock.toggle() }, modifier = Modifier.testTag("play")) { Text(if (playing) "Pause" else "Play") }
            TimeText(time, duration, Modifier.weight(1f))
            for (speed in PlaybackClock.SPEEDS) {
                FilterChip(selected = rate == speed, onClick = { clock.setRate(speed) },
                           label = { Text(String.format(Locale.ROOT, "%s×", speed.toString().removeSuffix(".0"))) })
            }
        }
    }
}

/** Its own scope, so that only this text follows the clock every frame. */
@Composable
private fun TimeText(time: () -> Double, duration: Double, modifier: Modifier) {
    Text("${formatTime(time())} / ${formatTime(duration)}", modifier = modifier.testTag("time"))
}
