package com.arbaba.chordifymobile.ui.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.data.Status
import com.arbaba.chordifymobile.music.formatTime
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import com.arbaba.chordifymobile.ui.AdaptiveLayout
import com.arbaba.chordifymobile.ui.ScreenTopBar
import com.arbaba.chordifymobile.ui.stageLabel
import com.arbaba.chordifymobile.ui.summary

/** The Library, the home screen (M7 spec §4.2): a top bar with Quick chord, Songwriter and Settings, every
 *  analysis with its progress or error, and one Add button (or the recording bar while recording). */
@Composable
fun LibraryScreen(onOpen: (String) -> Unit, onQuickChord: () -> Unit = {}, onSongwriter: () -> Unit = {},
                  onSettings: () -> Unit = {}, model: LibraryViewModel = viewModel()) {
    val context = LocalContext.current
    val analyses by model.analyses.collectAsStateWithLifecycle()
    val recording by model.recordingSeconds.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    var microphoneRefused by remember { mutableStateOf(false) }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun askForNotifications() {
        // Android 13 made notifications a permission; before it they are on unless the user turned them off.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            model.import(uri)
            askForNotifications()
        }
    }
    // Files passed between phones do not always keep their type, so the picker offers a little more;
    // the import refuses anything that is not an analysis.
    val importAnalysis = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importAnalysis(uri)
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneRefused = !granted
        if (granted) model.startRecording()
    }

    LibraryContent(
        analyses = analyses,
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
        onOpen = onOpen,
        onImportAnalysis = { importAnalysis.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
        onQuickChord = onQuickChord,
        onSongwriter = onSongwriter,
        onSettings = onSettings,
    )
}

const val MICROPHONE_REFUSED = "Chordify needs the microphone to record. You can allow it in the app's settings."

/** The Library without its view model, so tests can show it in any state. [onRecord] starts recording when
 *  [recordingSeconds] is null and stops it otherwise. */
@Composable
fun LibraryContent(
    analyses: List<Analysis>,
    recordingSeconds: Double?,
    message: String?,
    onPick: () -> Unit,
    onRecord: () -> Unit,
    onImportAnalysis: () -> Unit,
    onDismissMessage: () -> Unit,
    onOpenAppSettings: (() -> Unit)?,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpen: (String) -> Unit,
    onQuickChord: () -> Unit,
    onSongwriter: () -> Unit,
    onSettings: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<Analysis?>(null) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AdaptiveLayout(Modifier.fillMaxWidth()) { landscape ->
            ScreenTopBar("Chordify", onBack = null) {
                TopBarAction("Quick chord", Icons.Filled.Star, landscape, onQuickChord)
                TopBarAction("Songwriter", Icons.Filled.Edit, landscape, onSongwriter)
                TopBarAction("Settings", Icons.Filled.Settings, landscape, onSettings)
            }
        }
        AdaptiveLayout(Modifier.weight(1f).fillMaxWidth()) { landscape ->
            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    GridCells.Fixed(if (landscape) 2 else 1), Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (message != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
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
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text("Pick a song or record your instrument. Chordify finds the chords, the beat and the key, on your phone.",
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(analyses, key = { it.id }) { analysis ->
                        AnalysisRow(analysis, onCancel, onRetry,
                                    onDelete = { if (analysis.ownsFile) confirmDelete = analysis else onDelete(analysis.id) },
                                    onOpen = { onOpen(analysis.id) })
                    }
                }
                if (recordingSeconds == null) {
                    AddButton(onPick, onRecord, onImportAnalysis, Modifier.align(Alignment.BottomEnd).padding(16.dp))
                } else {
                    RecordingBar(recordingSeconds, onRecord, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                }
            }
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

/** A top bar action: icon and label when [labelled] (landscape), the icon alone with a description otherwise. */
@Composable
private fun TopBarAction(label: String, icon: ImageVector, labelled: Boolean, onClick: () -> Unit) {
    if (labelled) {
        TextButton(onClick = onClick) {
            Icon(icon, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label)
        }
    } else {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label) }
    }
}

/** The one Add button: Pick a song, Record, or Import an analysis (a .json export, which comes without audio). */
@Composable
private fun AddButton(onPick: () -> Unit, onRecord: () -> Unit, onImportAnalysis: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        // This Material version clears the semantics of the button's content, so the name is set here.
        ExtendedFloatingActionButton(onClick = { open = true }, modifier = Modifier.testTag("add").semantics { contentDescription = "Add" },
                                     icon = { Icon(Icons.Filled.Add, contentDescription = null) }, text = { Text("Add") })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Pick a song") }, onClick = { open = false; onPick() })
            DropdownMenuItem(text = { Text("Record") }, onClick = { open = false; onRecord() })
            DropdownMenuItem(text = { Text("Import an analysis (.json)") }, onClick = { open = false; onImportAnalysis() })
        }
    }
}

/** Replaces the Add button while recording: a red dot, the timer and the way to stop. */
@Composable
private fun RecordingBar(seconds: Double, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.testTag("recordingBar"), shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
            Spacer(Modifier.width(8.dp))
            Text("Recording ${formatTime(seconds)}") // not a live region: it would be read out every second
            Spacer(Modifier.width(12.dp))
            Button(onClick = onStop) { Text("Stop and analyse") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalysisRow(analysis: Analysis, onCancel: (String) -> Unit, onRetry: (String) -> Unit, onDelete: () -> Unit,
                        onOpen: () -> Unit) {
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
        false // the row goes away when the analysis does
    })
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().clip(CardDefaults.shape).background(MaterialTheme.colorScheme.error).padding(16.dp),
                contentAlignment = Alignment.CenterEnd) {
                Text("Delete", color = MaterialTheme.colorScheme.onError)
            }
        },
        modifier = Modifier.testTag("analysis-${analysis.id}"),
    ) {
        Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
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
