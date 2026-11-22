package com.arbaba.chordifymobile.quick

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arbaba.chordifymobile.ChordifyApp
import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.audio.AudioSource
import com.arbaba.chordifymobile.record.Recorder
import com.arbaba.chordifymobile.ui.AdaptiveLayout
import com.arbaba.chordifymobile.ui.ScreenTopBar
import com.arbaba.chordifymobile.ui.SectionTitle
import com.arbaba.chordifymobile.ui.TwoPanes
import com.arbaba.chordifymobile.ui.analysis.GuitarDiagram
import com.arbaba.chordifymobile.ui.analysis.percent
import com.arbaba.chordifymobile.ui.library.MICROPHONE_REFUSED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** What Quick chord is doing. */
sealed interface QuickState {
    data object Idle : QuickState
    data class Recording(val seconds: Double, val level: Float) : QuickState
    data object Working : QuickState
}

/** The chord found: its label, its full name and the model's confidence (0..1). */
data class QuickResult(val label: String, val name: String, val confidence: Double)

class QuickChordViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = (application as ChordifyApp).container.engine
    val state = MutableStateFlow<QuickState>(QuickState.Idle)
    val result = MutableStateFlow<QuickResult?>(null)
    val error = MutableStateFlow<String?>(null)
    private var stop: AtomicBoolean? = null

    /** Records until Stop, or for [RECORD_SECONDS]; call only with the microphone permission. */
    @SuppressLint("MissingPermission")
    fun record() {
        if (state.value != QuickState.Idle) return
        val stopped = AtomicBoolean(false).also { stop = it }
        val file = File(getApplication<Application>().cacheDir, "quick-chord.wav")
        error.value = null
        state.value = QuickState.Recording(0.0, 0f)
        viewModelScope.launch {
            try {
                var seconds = 0.0
                var level = 0f
                withContext(Dispatchers.IO) {
                    Recorder.record(getApplication(), file, isStopped = { stopped.get() || seconds >= RECORD_SECONDS },
                                    onLevel = { level = it }) {
                        seconds = it
                        state.value = QuickState.Recording(it, level)
                    }
                }
                identify(AudioSource.FromFile(file))
            } catch (failure: Exception) {
                error.value = "Could not record: ${failure.message}"
                state.value = QuickState.Idle
            } finally {
                file.delete()
                stop = null
            }
        }
    }

    fun stop() {
        stop?.set(true)
    }

    /** "Or pick a short clip". */
    fun pick(uri: Uri) = viewModelScope.launch { identify(AudioSource.FromUri(getApplication(), uri)) }

    private suspend fun identify(source: AudioSource) {
        state.value = QuickState.Working
        error.value = null
        try {
            val pcm = withContext(Dispatchers.Default) { decodeClip(source) }
            val engine = engine.concurrent()
            val (label, confidence) = withContext(Dispatchers.Default) { engine.quickChord(pcm) }
            result.value = QuickResult(label, quickChordName(label), confidence)
        } catch (failure: EngineException) {
            error.value = failure.message
        } catch (failure: Exception) {
            error.value = "Could not read this clip: ${failure.message}"
        } finally {
            state.value = QuickState.Idle
        }
    }
}

/** The Quick chord screen (M7 spec §4.3), opened from the Library. */
@Composable
fun QuickChordScreen(onBack: () -> Unit = {}, model: QuickChordViewModel = viewModel()) {
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    val result by model.result.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    var refused by remember { mutableStateOf(false) }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refused = !granted
        if (granted) model.record()
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.pick(uri) }
    QuickChordContent(
        state = state,
        result = result,
        error = error ?: if (refused) MICROPHONE_REFUSED else null,
        onRecord = {
            when {
                state is QuickState.Recording -> model.stop()
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                    refused = false
                    model.record()
                }
                else -> microphone.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onPick = { pick.launch(arrayOf("audio/*")) },
        onBack = onBack,
        onOpenAppSettings = if (refused) {
            {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                             Uri.fromParts("package", context.packageName, null)))
            }
        } else null,
    )
}

/** Quick chord without its view model, so tests can show it in any state: a top bar with Back, then the
 *  recording controls on one half and the chord found on the other (stacked in portrait). */
@Composable
fun QuickChordContent(state: QuickState, result: QuickResult?, error: String?, onRecord: () -> Unit, onPick: () -> Unit,
                      onOpenAppSettings: (() -> Unit)? = null, onBack: () -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Quick chord", onBack)
        AdaptiveLayout(Modifier.weight(1f).fillMaxWidth()) { landscape ->
            TwoPanes(landscape, Modifier.fillMaxSize(), first = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Strum one chord for two or three seconds.")
                    Button(onClick = onRecord, enabled = state != QuickState.Working, modifier = Modifier.testTag("recordChord")) {
                        Text(when (state) {
                            is QuickState.Recording -> "Stop"
                            QuickState.Working -> "Listening…"
                            QuickState.Idle -> "Record a chord"
                        })
                    }
                    if (state is QuickState.Recording) {
                        LinearProgressIndicator(progress = { min(1f, state.level * 4) },
                                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Input level" })
                        Text(recordingTime(state.seconds), style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = onPick, enabled = state == QuickState.Idle) { Text("Or pick a short clip") }
                    if (error != null) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        if (onOpenAppSettings != null) TextButton(onClick = onOpenAppSettings) { Text("Open the app's settings") }
                    }
                }
            }, second = {
                Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                       verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("Result")
                    if (result == null) {
                        Text("Your chord will appear here.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(result.name, fontSize = 48.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("quickName"))
                                Text("Confidence ${percent(result.confidence)}%", style = MaterialTheme.typography.bodySmall)
                            }
                            chordShape(result.label)?.let { GuitarDiagram(it, 0, width = 96.dp) }
                        }
                    }
                }
            })
        }
    }
}
