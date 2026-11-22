package com.arbaba.chordifymobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.arbaba.chordifymobile.data.Settings
import com.arbaba.chordifymobile.data.isAnalysisExport
import com.arbaba.chordifymobile.quick.QuickChordScreen
import com.arbaba.chordifymobile.songwriter.SongwriterScreen
import com.arbaba.chordifymobile.ui.AppBackground
import com.arbaba.chordifymobile.ui.ChordifyTheme
import com.arbaba.chordifymobile.ui.analysis.AnalysisScreen
import com.arbaba.chordifymobile.ui.settings.AboutScreen
import com.arbaba.chordifymobile.ui.settings.SettingsScreen
import com.arbaba.chordifymobile.ui.library.LibraryScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val container = (application as ChordifyApp).container
        // The Screen setting is applied before the window exists (the manifest's sensorLandscape is
        // the default for a first start). One small DataStore read per creation. The activity
        // handles orientation and size changes itself (manifest configChanges), so turning to the
        // setting's orientation re-lays the UI out instead of relaunching the activity.
        requestedOrientation = runBlocking { container.settings.screenOrientation.first() }.activityOrientation
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch { // later changes of the setting
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                container.settings.screenOrientation.distinctUntilChanged().collect { requestedOrientation = it.activityOrientation }
            }
        }
        setContent { ChordifyTheme { AppBackground { ChordifyNavigation() } } }
        if (savedInstanceState == null) importShared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importShared(intent)
    }

    /** "Share to" (ACTION_SEND) and "Open with" (ACTION_VIEW): an audio file is analysed, a .json
     *  export is added as it is. The import runs in the app's scope, not the activity's, so that a
     *  relaunch (a rotation, a change of the Screen setting) cannot cancel it half-way. */
    private fun importShared(intent: Intent) {
        val uri = sharedFile(intent) ?: return
        val app = application as ChordifyApp
        val container = app.container
        val type = intent.type ?: contentResolver.getType(uri)
        container.scope.launch {
            try {
                if (isAnalysisExport(type, uri.lastPathSegment)) {
                    container.library.importAnalysis(uri)
                    Toast.makeText(app, "The analysis is in the Library.", Toast.LENGTH_SHORT).show()
                } else {
                    container.library.import(uri, Settings.options(container.settings.majorMinorOnly.first()))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(app, "Could not read this file: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        /** The file an intent hands the app, if it is a share or an "open with". */
        fun sharedFile(intent: Intent): Uri? = when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
    }
}

/** The Library is home; the other screens open from it, and Back returns to it (M7 spec §4.1). */
@Composable
fun ChordifyNavigation() {
    val navigation = rememberNavController()
    val back: () -> Unit = { navigation.popBackStack() }
    NavHost(
        navigation, startDestination = "library",
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        composable("library") {
            LibraryScreen(
                onOpen = { navigation.navigate("analysis/$it") },
                onQuickChord = { navigation.navigate("quick") },
                onSongwriter = { navigation.navigate("write") },
                onSettings = { navigation.navigate("settings") },
            )
        }
        composable("analysis/{id}") { entry -> AnalysisScreen(entry.arguments!!.getString("id")!!, onBack = back) }
        composable("quick") { QuickChordScreen(onBack = back) }
        composable("write") { SongwriterScreen(onBack = back) }
        composable("settings") { SettingsScreen(onAbout = { navigation.navigate("about") }, onBack = back) }
        composable("about") { AboutScreen(onBack = back) }
    }
}
