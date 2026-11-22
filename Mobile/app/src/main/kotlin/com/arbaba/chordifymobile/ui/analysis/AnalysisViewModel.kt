package com.arbaba.chordifymobile.ui.analysis

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arbaba.chordifymobile.ChordifyApp
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.data.ExportEdits
import com.arbaba.chordifymobile.edit.ChordEdits
import com.arbaba.chordifymobile.edit.EditActions
import com.arbaba.chordifymobile.edit.EditController
import com.arbaba.chordifymobile.edit.EditsLoad
import com.arbaba.chordifymobile.edit.rebuildWith
import com.arbaba.chordifymobile.edit.spansOf
import com.arbaba.chordifymobile.edit.EditUiState
import com.arbaba.chordifymobile.data.ExportFormat
import com.arbaba.chordifymobile.data.exportFile
import com.arbaba.chordifymobile.data.shareIntent
import com.arbaba.chordifymobile.data.Status
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.playback.ExoAudioPlayer
import com.arbaba.chordifymobile.playback.PlaybackClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether the analysed audio can be played: still being checked, playable, or gone. */
/** How close to a looped chord's end counts as its end, in seconds. */
private const val LOOP_MARGIN = 0.05

enum class AudioFile { CHECKING, PLAYABLE, MISSING }

class AnalysisViewModel(application: Application, private val id: String) : AndroidViewModel(application), EditActions {
    private val library = (application as ChordifyApp).container.library

    val analysis: StateFlow<Analysis?> = library.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    /** The shown result: the rebuilt one when edits are saved, otherwise the model's. */
    val result = MutableStateFlow<AnalysisResult?>(null)

    private val container = (application as ChordifyApp).container
    private val _original = MutableStateFlow<AnalysisResult?>(null)
    private val _hasEdits = MutableStateFlow(false)
    private val _savedShapes = MutableStateFlow<Map<String, List<Int>>>(emptyMap())
    private val _editsDamaged = MutableStateFlow(false)
    private val _edit = MutableStateFlow<EditUiState?>(null)

    /** The model's result. */
    val original: StateFlow<AnalysisResult?> = _original
    val hasEdits: StateFlow<Boolean> = _hasEdits
    val savedShapes: StateFlow<Map<String, List<Int>>> = _savedShapes
    val editsDamaged: StateFlow<Boolean> = _editsDamaged

    /** The editing session; null when not editing. */
    val edit: StateFlow<EditUiState?> = _edit
    private var controller: EditController? = null
    private var savedEdits: ChordEdits? = null
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
                    _original.value = loaded
                    val shown = loadEdits(loaded) ?: loaded
                    controller = makeController(loaded)
                    result.value = shown
                    connect(row)
                }
            }
        }
        viewModelScope.launch { followLoop() }
    }

    /** The result rebuilt from the saved edits, or null when there are none (or they are damaged). */
    private suspend fun loadEdits(loaded: AnalysisResult): AnalysisResult? {
        when (val load = library.edits(id)) {
            EditsLoad.None -> return null
            EditsLoad.Damaged -> _editsDamaged.value = true
            is EditsLoad.Loaded -> try {
                val rebuilt = rebuildWith(container.engine, loaded, load.edits.chords)
                savedEdits = load.edits
                _savedShapes.value = load.edits.shapes
                _hasEdits.value = true
                return rebuilt
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _editsDamaged.value = true
            }
        }
        return null
    }

    private fun makeController(loaded: AnalysisResult): EditController {
        val made = EditController(
            duration = loaded.source.duration,
            original = loaded,
            rebuild = { spans -> rebuildWith(container.engine, loaded, spans) },
            save = { edits -> library.saveEdits(id, edits) },
            clear = { library.clearEdits(id) },
            scope = viewModelScope,
            onFinished = { edits, shown ->
                savedEdits = edits
                _hasEdits.value = edits != null
                _savedShapes.value = edits?.shapes ?: emptyMap()
                _editsDamaged.value = false
                result.value = shown ?: loaded
            },
        )
        viewModelScope.launch { made.state.collect { _edit.value = it } }
        return made
    }

    /** While looping a chord, jumps back to its start when playback leaves it. */
    private suspend fun followLoop() {
        edit.collectLatest { state ->
            val span = state?.takeIf { it.looping }?.selected?.let { state.spans.getOrNull(it) } ?: return@collectLatest
            val playing = clock ?: return@collectLatest
            playing.time.collect { time ->
                if (time >= span.end - LOOP_MARGIN || time < span.start) {
                    val wasPlaying = playing.playing.value
                    playing.seek(span.start)
                    if (wasPlaying || time >= span.end - LOOP_MARGIN) playing.play()
                }
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
        val edits = if (hasEdits.value && model != null) ExportEdits(model.chords, savedShapes.value) else null
        val app = getApplication<Application>()
        open(withContext(Dispatchers.IO) { shareIntent(app, exportFile(app, row.displayName, analysed, format, edits), format) })
    }

    // -- editing ---------------------------------------------------------------------------------

    override fun startEditing() {
        val loaded = original.value ?: return
        if (edit.value != null) return
        controller?.start(savedEdits ?: ChordEdits(chords = spansOf(loaded)))
    }

    override fun cancelEditing() { controller?.cancel() }
    override fun done() { controller?.done() }
    override fun restore() { controller?.restore() }
    override fun select(index: Int?) { controller?.select(index) }
    override fun moveEdge(edge: Int, time: Double) { controller?.moveEdge(edge, time) }
    override fun setStart(index: Int, time: Double) { controller?.setStart(index, time) }
    override fun setEnd(index: Int, time: Double) { controller?.setEnd(index, time) }
    override fun split(index: Int, time: Double) { controller?.split(index, time) }
    override fun mergeWithNext(index: Int) { controller?.mergeWithNext(index) }
    override fun rename(index: Int, soundingLabel: String) { controller?.rename(index, soundingLabel) }
    override fun setShape(shapeLabel: String, shape: List<Int>?) { controller?.setShape(shapeLabel, shape) }
    override fun undo() { controller?.undo() }
    override fun redo() { controller?.redo() }
    override fun toggleLoop() { controller?.toggleLoop() }

    /** The chord's own runner-up readings first, then what usually follows the chords before it. */
    override suspend fun suggestions(index: Int): List<String> {
        val state = edit.value ?: return emptyList()
        val chord = state.preview.chords.getOrNull(index) ?: return emptyList()
        val key = state.preview.key.global
        val before = state.preview.chords.take(index).map { it.label }.filter { it != "N" }
        val next = if (before.isEmpty()) emptyList() else try {
            val engine = container.engine.concurrent()
            withContext(Dispatchers.Default) { engine.nextChords(before, "${key.tonic} ${key.mode}", 6).predictions.map { it.label } }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            emptyList()
        }
        return (chord.alternatives.map { it.label } + next).distinct().filter { it != chord.label }.take(6)
    }

    override fun onCleared() {
        clock?.pause()
        player?.release()
    }
}
