package com.arbaba.chordifymobile.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arbaba.chordifymobile.ChordifyApp
import com.arbaba.chordifymobile.ui.analysis.CardTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** The source code, which the GPL asks to offer. */
const val SOURCE_URL = "https://github.com/mysterI0s/Chordify"

/** "12 analyses · 3.4 MB of results, recordings and copied files" */
fun storageLine(analyses: Int, bytes: Long): String =
    "$analyses ${if (analyses == 1) "analysis" else "analyses"} · " +
        String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0) + " of results, recordings and copied files"

/** The bytes the files under [dirs] take. */
fun usedBytes(dirs: List<File>): Long = dirs.sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as ChordifyApp).container
    val majorMinorOnly: StateFlow<Boolean> = container.settings.majorMinorOnly.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val storage: StateFlow<String?> = container.library.analyses
        .map { rows -> storageLine(rows.size, usedBytes(listOf("results", "recordings", "imports").map { File(application.filesDir, it) })) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setMajorMinorOnly(value: Boolean) = viewModelScope.launch { container.settings.setMajorMinorOnly(value) }
}

/** The Settings tab: the analysis default, the space the library takes, and About. */
@Composable
fun SettingsScreen(onAbout: () -> Unit, model: SettingsViewModel = viewModel()) {
    val majorMinorOnly by model.majorMinorOnly.collectAsStateWithLifecycle()
    val storage by model.storage.collectAsStateWithLifecycle()
    SettingsContent(majorMinorOnly, model::setMajorMinorOnly, storage, onAbout)
}

@Composable
fun SettingsContent(majorMinorOnly: Boolean, onMajorMinorOnly: (Boolean) -> Unit, storage: String?, onAbout: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitle("Analysis")
                Row(Modifier.fillMaxWidth().toggleable(majorMinorOnly, role = Role.Switch, onValueChange = onMajorMinorOnly)
                        .testTag("majorMinorOnly"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Major and minor chords only")
                        Text("New analyses show C and G instead of Cmaj7 and G7.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = majorMinorOnly, onCheckedChange = null)
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitle("Storage")
                Text(storage ?: "Counting…")
                Text("Everything stays on this phone. Deleting an analysis in the Library frees its space.",
                     style = MaterialTheme.typography.bodySmall)
            }
        }
        Card(Modifier.fillMaxWidth().clickable(onClickLabel = "Open", onClick = onAbout).testTag("about")) {
            Column(Modifier.padding(16.dp)) {
                Text("About Chordify", fontWeight = FontWeight.SemiBold)
                Text("Licence, source code, third-party software and credits", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** About (spec §6.2): licence, source, third-party notices and dataset credits. */
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as ChordifyApp).container
    val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    AboutContent(version, container.modelIds, onSource = {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)))
    })
}

@Composable
fun AboutContent(version: String, models: Map<String, String>, onSource: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Chordify", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Version $version", style = MaterialTheme.typography.bodySmall)
        Text("Chordify finds the chords, the beat and the key of a song on your phone. Nothing is sent anywhere: " +
             "the app has no network access.")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitle("Licence")
                Text("Chordify is free software under the GNU General Public License, version 3 or later " +
                     "(GPL-3.0-or-later). You may share and change it under its terms. It bundles NNLS Chroma, " +
                     "which is GPL-2.0-or-later.")
                TextButton(onClick = onSource) { Text("Source code") }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitle("Third-party software")
                for (notice in NOTICES) {
                    Column {
                        Text("${notice.name} · ${notice.licence}", fontWeight = FontWeight.SemiBold)
                        Text(notice.use, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitle("Datasets")
                Text(DATASET_CREDITS)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitle("Models")
                for ((role, id) in models) Text("$role: $id", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** One bundled component, its licence and what the app uses it for (Mobile/THIRD_PARTY_NOTICES.md). */
data class Notice(val name: String, val licence: String, val use: String)

val NOTICES = listOf(
    Notice("NNLS Chroma", "GPL-2.0-or-later", "The pitch features the chord model reads."),
    Notice("Vamp plugin SDK", "MIT-style", "The plugin host that runs NNLS Chroma."),
    Notice("libsoxr", "LGPL-2.1-or-later", "Resampling audio to 44.1 kHz."),
    Notice("ONNX Runtime", "MIT", "Runs the chord model."),
    Notice("AndroidX, Jetpack Compose and Material 3", "Apache-2.0", "The screens, the library and the background jobs."),
    Notice("Media3 ExoPlayer", "Apache-2.0", "Playback."),
    Notice("Kotlin and kotlinx", "Apache-2.0", "The language, coroutines and JSON."),
)

const val DATASET_CREDITS = "The chord model was trained on McGill Billboard, POP909 and GuitarSet (CC BY 4.0), and on " +
    "synthetic renders. The progression model was built from the McGill Billboard chord annotations, read through ChoCo."
