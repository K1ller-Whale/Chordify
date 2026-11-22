package com.arbaba.chordifymobile.ui.analysis

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arbaba.chordifymobile.ChordifyApp
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.data.ExportEdits
import com.arbaba.chordifymobile.edit.EditActions
import com.arbaba.chordifymobile.edit.EditUiState
import com.arbaba.chordifymobile.edit.SongEdits
import com.arbaba.chordifymobile.edit.loopJump
import com.arbaba.chordifymobile.edit.rebuildWith
import com.arbaba.chordifymobile.data.ExportFormat
import com.arbaba.chordifymobile.data.exportFile
import com.arbaba.chordifymobile.data.shareIntent
import com.arbaba.chordifymobile.data.Status
import com.arbaba.chordifymobile.engine.analysis.nextChords
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.playback.ExoAudioPlayer
import com.arbaba.chordifymobile.playback.PlaybackClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether the analysed audio can be played: still being checked, playable, or gone. */
enum class AudioFile { CHECKING, PLAYABLE, MISSING }

class AnalysisViewModel(application: Application, private val id: String) : AndroidViewModel(application), EditActions {
    private val container = (application as ChordifyApp).container
    private val library = container.library

    val analysis: StateFlow<Analysis?> = library.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The song's edits and its editing session, on the library's files and the engine's models. */
    private val edits = SongEdits(
        scope = viewModelScope,
        rebuild = { result, spans -> rebuildWith(container.engine, result, spans) },
        load = { library.edits(id) },
        save = { library.saveEdits(id, it) },
        clear = { library.clearEdits(id) },
        setAside = { library.setAsideDamagedEdits(id) },
        next = { chords, key ->
            val models = container.engine.models()
            withContext(Dispatchers.Default) { nextChords(models, chords, key, 6).predictions.map { it.label } }
        },
    )

    /** The shown result: the rebuilt one when edits are saved, otherwise the model's. */
    val result: StateFlow<AnalysisResult?> = edits.shown
    /** The model's result. */
    val original: StateFlow<AnalysisResult?> = edits.original
    val hasEdits: StateFlow<Boolean> = edits.hasEdits
    val savedShapes: StateFlow<Map<String, List<Int>>> = edits.savedShapes
    val editsDamaged: StateFlow<Boolean> = edits.damaged

    /** The editing session; null when not editing. */
    val edit: StateFlow<EditUiState?> = edits.edit

    /** What the screen says once, outside an editing session (a failed restore, chords that could not be rebuilt). */
    val messages: Flow<String> = edits.messages
    val notation = MutableStateFlow(Notation.LETTERS)
    val audio = MutableStateFlow(AudioFile.CHECKING)
    val mismatch = MutableStateFlow(false)

    /** The waveform of the file that plays, once it is made (null while it is, or without a file). */
    val peaks = MutableStateFlow<FloatArray?>(null)
    private var peaksJob: Job? = null

    /** The clock of the completed result, made once it is loaded. */
    var clock: PlaybackClock? = null
        private set
    private var player: ExoAudioPlayer? = null

    init {
        viewModelScope.launch {
            analysis.collect { row ->
                if (row?.status == Status.COMPLETED && result.value == null) {
                    val loaded = library.result(id) ?: return@collect
                    clock = PlaybackClock(loaded.source.duration)
                    edits.open(loaded)
                    connect(row)
                }
            }
        }
        viewModelScope.launch { followLoop() }
    }

    /** While looping a chord, jumps back to its start when playback leaves it. */
    private suspend fun followLoop() {
        edit.collectLatest { state ->
            val span = state?.takeIf { it.looping }?.selected?.let { state.spans.getOrNull(it) } ?: return@collectLatest
            val playing = clock ?: return@collectLatest
            var wasRunning = playing.playing.value
            combine(playing.time, playing.playing, ::Pair).collect { (time, running) ->
                loopJump(time, running, wasRunning, span.start, span.end)?.let {
                    playing.seek(span.start)
                    if (it.play) playing.play()
                }
                wasRunning = running
            }
        }
    }

    fun setCapo(capo: Int) = viewModelScope.launch { library.setCapo(id, capo) }

    fun retry() = viewModelScope.launch { library.retry(id) }

    /** "Pick the file again" when it went missing: plays it, warning when it is another file. */
    fun attach(uri: Uri) = viewModelScope.launch {
        mismatch.value = !library.attach(id, uri)
        library.get(id)?.let { connect(it) }
    }

    private suspend fun connect(row: Analysis) {
        val playable = library.isReadable(row)
        audio.value = if (playable) AudioFile.PLAYABLE else AudioFile.MISSING
        player?.release()
        player = if (playable) ExoAudioPlayer(getApplication(), Uri.parse(row.fileUri)) else null
        clock?.attach(player)
        peaksJob?.cancel()
        peaks.value = null
        val duration = result.value?.source?.duration
        if (playable && duration != null) peaksJob = viewModelScope.launch { peaks.value = library.peaks(row, duration) }
    }

    /** Writes the export and hands the share sheet to [open] (spec §6.2). */
    fun export(format: ExportFormat, open: (Intent) -> Unit) = viewModelScope.launch {
        val row = analysis.value ?: return@launch
        val analysed = result.value ?: return@launch
        val model = original.value
        val withEdits = if (edits.showsEdits && model != null) ExportEdits(model.chords, savedShapes.value) else null
        val app = getApplication<Application>()
        open(withContext(Dispatchers.IO) { shareIntent(app, exportFile(app, row.displayName, analysed, format, withEdits), format) })
    }

    // -- editing, all of it in SongEdits --------------------------------------------------------

    override fun startEditing() = edits.startEditing()
    override fun cancelEditing() = edits.cancelEditing()
    override fun done() = edits.done()
    override fun restore() = edits.restore()
    override fun select(index: Int?) = edits.select(index)
    override fun moveEdge(edge: Int, time: Double) = edits.moveEdge(edge, time)
    override fun setStart(index: Int, time: Double) = edits.setStart(index, time)
    override fun setEnd(index: Int, time: Double) = edits.setEnd(index, time)
    override fun split(index: Int, time: Double) = edits.split(index, time)
    override fun mergeWithNext(index: Int) = edits.mergeWithNext(index)
    override fun rename(index: Int, soundingLabel: String) = edits.rename(index, soundingLabel)
    override fun setShape(shapeLabel: String, shape: List<Int>?) = edits.setShape(shapeLabel, shape)
    override fun undo() = edits.undo()
    override fun redo() = edits.redo()
    override fun toggleLoop() = edits.toggleLoop()
    override suspend fun suggestions(index: Int): List<String> = edits.suggestions(index)

    override fun onCleared() {
        clock?.pause()
        player?.release()
    }
}
