package com.arbaba.chordifymobile.ui.library

import android.annotation.SuppressLint
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arbaba.chordifymobile.ChordifyApp
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.data.Settings
import com.arbaba.chordifymobile.record.Recorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as ChordifyApp).container
    private val library = container.library

    val analyses: StateFlow<List<Analysis>> =
        library.analyses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Seconds recorded so far, or null when not recording. */
    val recordingSeconds = MutableStateFlow<Double?>(null)

    /** A problem to show above the list (a file that could not be read), until dismissed. */
    val message = MutableStateFlow<String?>(null)

    private var stopRecording: AtomicBoolean? = null

    fun import(uri: Uri) {
        viewModelScope.launch {
            try {
                library.import(uri, options())
            } catch (error: Exception) {
                message.value = "Could not read this file: ${error.message}"
            }
        }
    }

    /** "Import an analysis": a .json export from another phone or the web app. */
    fun importAnalysis(uri: Uri) {
        viewModelScope.launch {
            try {
                library.importAnalysis(uri)
            } catch (error: Exception) {
                message.value = "Could not import this file: ${error.message}"
            }
        }
    }

    /** Starts recording; call only with the microphone permission granted. */
    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (stopRecording != null) return
        val stop = AtomicBoolean(false).also { stopRecording = it }
        val started = Date()
        val file = File(File(getApplication<Application>().filesDir, "recordings").apply { mkdirs() },
                        "recording-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(started)}.wav")
        recordingSeconds.value = 0.0
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Recorder.record(getApplication(), file, isStopped = stop::get) { recordingSeconds.value = it }
                }
                val name = "Recording ${SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(started)}"
                library.addRecording(file, name, options())
            } catch (error: Exception) {
                file.delete()
                message.value = "Could not record: ${error.message}"
            } finally {
                recordingSeconds.value = null
                stopRecording = null
            }
        }
    }

    fun stopRecording() {
        stopRecording?.set(true)
    }

    fun cancel(id: String) = viewModelScope.launch { library.cancel(id) }

    fun retry(id: String) = viewModelScope.launch { library.retry(id) }

    fun delete(id: String) = viewModelScope.launch { library.delete(id) }

    private suspend fun options() = Settings.options(container.settings.majorMinorOnly.first())
}
