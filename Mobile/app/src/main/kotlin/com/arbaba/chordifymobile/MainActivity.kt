package com.arbaba.chordifymobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.arbaba.chordifymobile.data.Settings
import com.arbaba.chordifymobile.quick.QuickChordScreen
import com.arbaba.chordifymobile.songwriter.SongwriterScreen
import com.arbaba.chordifymobile.ui.ChordifyTheme
import com.arbaba.chordifymobile.ui.analysis.AnalysisScreen
import com.arbaba.chordifymobile.ui.settings.AboutScreen
import com.arbaba.chordifymobile.ui.settings.SettingsScreen
import com.arbaba.chordifymobile.ui.library.LibraryScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ChordifyTheme { ChordifyNavigation() } }
        if (savedInstanceState == null) importShared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importShared(intent)
    }

    /** "Share to" (ACTION_SEND) and "Open with" (ACTION_VIEW) an audio file: add it to the library. */
    private fun importShared(intent: Intent) {
        val uri = sharedAudio(intent) ?: return
        val container = (application as ChordifyApp).container
        lifecycleScope.launch {
            try {
                container.library.import(uri, Settings.options(container.settings.majorMinorOnly.first()))
            } catch (error: Exception) {
                Toast.makeText(this@MainActivity, "Could not read this file: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        /** The audio file an intent hands the app, if it is a share or an "open with". */
        fun sharedAudio(intent: Intent): Uri? = when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    LIBRARY("library", "Library", Icons.AutoMirrored.Filled.List),
    QUICK_CHORD("quick", "Quick chord", Icons.Filled.Star),
    SONGWRITER("write", "Songwriter", Icons.Filled.Edit),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

/** The four tabs of spec §6.2; the analysis screen opens from the Library, About from Settings. */
@Composable
fun ChordifyNavigation() {
    val navigation = rememberNavController()
    val current by navigation.currentBackStackEntryAsState()
    Scaffold(bottomBar = {
        NavigationBar {
            for (tab in Tab.entries) {
                NavigationBarItem(
                    selected = current?.destination?.route == tab.route,
                    onClick = {
                        navigation.navigate(tab.route) {
                            popUpTo(navigation.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(tab.label) },
                )
            }
        }
    }) { padding ->
        NavHost(navigation, startDestination = Tab.LIBRARY.route, modifier = Modifier.padding(padding)) {
            composable(Tab.LIBRARY.route) { LibraryScreen(onOpen = { navigation.navigate("analysis/$it") }) }
            composable("analysis/{id}") { entry -> AnalysisScreen(entry.arguments!!.getString("id")!!) }
            composable(Tab.QUICK_CHORD.route) { QuickChordScreen() }
            composable(Tab.SONGWRITER.route) { SongwriterScreen() }
            composable(Tab.SETTINGS.route) { SettingsScreen(onAbout = { navigation.navigate("about") }) }
            composable("about") { AboutScreen() }
        }
    }
}
