package com.arbaba.chordifymobile.ui.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.data.Status
import com.arbaba.chordifymobile.ui.formatTime
import com.arbaba.chordifymobile.ui.stageLabel
import com.arbaba.chordifymobile.ui.summary

/** The Library tab (spec §6.2): pick a file or record, the "Major and minor only" default, and
 *  every analysis with its progress or error. */
@Composable
fun LibraryScreen(model: LibraryViewModel = viewModel()) {
    val context = LocalContext.current
    val analyses by model.analyses.collectAsStateWithLifecycle()
    val majorMinorOnly by model.majorMinorOnly.collectAsStateWithLifecycle()
    val recording by model.recordingSeconds.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    var microphoneRefused by remember { mutableStateOf(false) }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun askForNotifications() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            model.import(uri)
            askForNotifications()
        }
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneRefused = !granted
        if (granted) model.startRecording()
    }

    LibraryContent(
        analyses = analyses,
        majorMinorOnly = majorMinorOnly,
        recordingSeconds = recording,
        message = message ?: if (microphoneRefused) MICROPHONE_REFUSED else null,
        onPick = { pick.launch(arrayOf("audio/*")) },
        onRecord = {
            if (recording != null) {
                model.stopRecording()
            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                model.startRecording()
                askForNotifications()
            } else {
                microphone.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onMajorMinorOnly = model::setMajorMinorOnly,
        onDismissMessage = {
            model.message.value = null
            microphoneRefused = false
        },
        onOpenAppSettings = if (microphoneRefused) {
            {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                             Uri.fromParts("package", context.packageName, null)))
            }
        } else null,
        onCancel = { model.cancel(it) },
        onRetry = { model.retry(it) },
        onDelete = { model.delete(it) },
    )
}

const val MICROPHONE_REFUSED = "Chordify needs the microphone to record. You can allow it in the app's settings."

/** The Library without its view model, so tests can show it in any state. */
@Composable
fun LibraryContent(
    analyses: List<Analysis>,
    majorMinorOnly: Boolean,
    recordingSeconds: Double?,
    message: String?,
    onPick: () -> Unit,
    onRecord: () -> Unit,
    onMajorMinorOnly: (Boolean) -> Unit,
    onDismissMessage: () -> Unit,
    onOpenAppSettings: (() -> Unit)?,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<Analysis?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
               verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onPick, enabled = recordingSeconds == null) { Text("Pick a file") }
                OutlinedButton(onClick = onRecord, modifier = Modifier.testTag("record")) {
                    Text(if (recordingSeconds == null) "Record" else "Stop and analyse (${formatTime(recordingSeconds)})")
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Major and minor chords only (C instead of Cmaj7, G instead of G7)", Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Switch(checked = majorMinorOnly, onCheckedChange = onMajorMinorOnly, modifier = Modifier.testTag("majorMinorOnly"))
            }
        }
        if (message != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        Row {
                            if (onOpenAppSettings != null) TextButton(onClick = onOpenAppSettings) { Text("Open settings") }
                            TextButton(onClick = onDismissMessage) { Text("Dismiss") }
                        }
                    }
                }
            }
        }
        if (analyses.isEmpty()) {
            item {
                Text("Pick a song or record your instrument. Chordify finds the chords, the beat and the key, on your phone.",
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(analyses, key = { it.id }) { analysis ->
            AnalysisRow(analysis, onCancel, onRetry, onDelete = { if (analysis.ownsFile) confirmDelete = analysis else onDelete(analysis.id) })
        }
    }
    confirmDelete?.let { analysis ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this analysis?") },
            text = { Text("The recording of \"${analysis.displayName}\" is deleted with it.") },
            confirmButton = {
                TextButton(onClick = { onDelete(analysis.id); confirmDelete = null }, Modifier.testTag("confirmDelete")) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalysisRow(analysis: Analysis, onCancel: (String) -> Unit, onRetry: (String) -> Unit, onDelete: () -> Unit) {
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
        false // the row goes away when the analysis does
    })
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error).padding(16.dp), contentAlignment = Alignment.CenterEnd) {
                Text("Delete", color = MaterialTheme.colorScheme.onError)
            }
        },
        modifier = Modifier.testTag("analysis-${analysis.id}"),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(analysis.displayName, fontWeight = FontWeight.SemiBold)
                when (analysis.status) {
                    Status.QUEUED, Status.RUNNING -> {
                        Text(stageLabel(analysis.stage), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(progress = { analysis.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { onCancel(analysis.id) }) { Text("Cancel") }
                    }
                    Status.COMPLETED -> Text(summary(analysis), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Status.FAILED -> {
                        Text("Could not analyse this audio", color = MaterialTheme.colorScheme.error)
                        Text(analysis.errorMessage.orEmpty())
                        Text("Error code: ${analysis.errorCode}", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { onRetry(analysis.id) }) { Text("Try again") }
                    }
                    Status.CANCELLED -> {
                        Text("Cancelled", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { onRetry(analysis.id) }) { Text("Analyse") }
                    }
                }
            }
        }
    }
}
