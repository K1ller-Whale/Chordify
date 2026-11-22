package com.arbaba.chordifymobile.ui.analysis

import android.app.Application
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
import androidx.compose.material3.Card
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
import com.arbaba.chordifymobile.engine.model.AnalysisResult
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
) {
    val result = remember(analysed, capo) { withCapo(analysed, capo) }
    val suggestion = remember(analysed) { suggestCapo(analysed.chords) }
    // The time is read by small scopes only (the Chord panel's countdown, the strip, the seek bar
    // and the time text), so the rest of the screen recomposes when the chord changes, not every
    // frame (spec §6.3).
    val time: State<Double> = clock.time.collectAsState()
    val playing by clock.playing.collectAsState()
    val rate by clock.rate.collectAsState()
    val starts = remember(result) { result.chords.map { it.start } }
    val index by remember(starts) { derivedStateOf { chordIndexAt(starts, time.value) } }
    val chord = result.chords[index]
    val sounding = analysed.chords.getOrNull(index)?.display
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

    // The no-audio banner (when there is one) above the stage, so it hides none of it; then the chord strip.
    val stageColumn: @Composable ColumnScope.() -> Unit = {
        NoAudioBanner(audio, mismatch, onPickFile, Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp))
        Stage(chord, notation, capo, sounding, onSwipe = { clock.seek(chordStep(starts, clock.time.value, it)) },
              modifier = Modifier.weight(1f).fillMaxWidth())
        ChordStrip(result, time, index, notation, peaks, surprising, onSeek = { clock.seek(it) })
    }
    // The tab row over the selected panel. Compact tabs (labelLarge, 4 dp padding instead of
    // Material's 16 dp) so that all five labels fit in the 40% of a landscape phone.
    val panelColumn: @Composable ColumnScope.() -> Unit = {
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

    Column(Modifier.fillMaxSize().then(keys).testTag("analysis")) {
        AnalysisTopBar(title, analysed, capo, suggestion, notation, onBack, onCapo, onNotation, onExport, onPickFile)
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
                     onRate = clock::setRate)
    }
}

/** The chord strip's height, which the portrait stage's 40% does not include. */
private val STRIP_HEIGHT = 72.dp
