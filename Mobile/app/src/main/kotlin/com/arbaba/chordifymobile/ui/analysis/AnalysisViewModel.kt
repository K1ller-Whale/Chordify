package com.arbaba.chordifymobile.ui.analysis

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arbaba.chordifymobile.ChordifyApp
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.data.Status
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.playback.ExoAudioPlayer
import com.arbaba.chordifymobile.playback.PlaybackClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Whether the analysed audio can be played: still being checked, playable, or gone. */
enum class AudioFile { CHECKING, PLAYABLE, MISSING }

class AnalysisViewModel(application: Application, private val id: String) : AndroidViewModel(application) {
    private val library = (application as ChordifyApp).container.library

    val analysis: StateFlow<Analysis?> = library.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val result = MutableStateFlow<AnalysisResult?>(null)
    val notation = MutableStateFlow(Notation.LETTERS)
    val audio = MutableStateFlow(AudioFile.CHECKING)
    val mismatch = MutableStateFlow(false)

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
    }

    override fun onCleared() {
        clock?.pause()
        player?.release()
    }
}
