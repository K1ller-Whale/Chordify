package com.arbaba.chordifymobile.ui.analysis

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arbaba.chordifymobile.ChordifyApp
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.edit.EditActions
import com.arbaba.chordifymobile.edit.EditUiState
import com.arbaba.chordifymobile.data.ExportFormat
import com.arbaba.chordifymobile.data.exportFile
import com.arbaba.chordifymobile.data.shareIntent
import com.arbaba.chordifymobile.data.Status
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.playback.ExoAudioPlayer
import com.arbaba.chordifymobile.playback.PlaybackClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether the analysed audio can be played: still being checked, playable, or gone. */
enum class AudioFile { CHECKING, PLAYABLE, MISSING }

class AnalysisViewModel(application: Application, private val id: String) : AndroidViewModel(application), EditActions {
    private val library = (application as ChordifyApp).container.library

    val analysis: StateFlow<Analysis?> = library.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    /** The shown result: the rebuilt one when edits are saved, otherwise the model's. */
    val result = MutableStateFlow<AnalysisResult?>(null)

    /** The model's result. */
    val original: StateFlow<AnalysisResult?> = MutableStateFlow(null)
    val hasEdits: StateFlow<Boolean> = MutableStateFlow(false)
    val savedShapes: StateFlow<Map<String, List<Int>>> = MutableStateFlow(emptyMap())
    val editsDamaged: StateFlow<Boolean> = MutableStateFlow(false)

    /** The editing session; null when not editing. */
    val edit: StateFlow<EditUiState?> = MutableStateFlow(null)
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
                    result.value = loaded
                    connect(row)
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
        val app = getApplication<Application>()
        open(withContext(Dispatchers.IO) { shareIntent(app, exportFile(app, row.displayName, analysed, format), format) })
    }

    // -- editing (V1) ---------------------------------------------------------------------------

    override fun startEditing() {}
    override fun cancelEditing() {}
    override fun done() {}
    override fun restore() {}
    override fun select(index: Int?) {}
    override fun moveEdge(edge: Int, time: Double) {}
    override fun setStart(index: Int, time: Double) {}
    override fun setEnd(index: Int, time: Double) {}
    override fun split(index: Int, time: Double) {}
    override fun mergeWithNext(index: Int) {}
    override fun rename(index: Int, soundingLabel: String) {}
    override fun setShape(shapeLabel: String, shape: List<Int>?) {}
    override fun undo() {}
    override fun redo() {}
    override fun toggleLoop() {}
    override suspend fun suggestions(index: Int): List<String> = emptyList()

    override fun onCleared() {
        clock?.pause()
        player?.release()
    }
}
