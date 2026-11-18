package com.arbaba.chordifymobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.arbaba.chordifymobile.ui.ChordifyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ChordifyTheme { ChordifyNavigation() } }
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    LIBRARY("library", "Library", Icons.AutoMirrored.Filled.List),
    QUICK_CHORD("quick", "Quick chord", Icons.Filled.Star),
    SONGWRITER("write", "Songwriter", Icons.Filled.Edit),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

/** The four tabs of spec §6.2. The Library comes with the library screen; Quick chord, Songwriter
 *  and Settings arrive in milestone M6. */
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
            composable(Tab.LIBRARY.route) { NotBuiltYet("Library") }
            composable(Tab.QUICK_CHORD.route) { NotBuiltYet("Quick chord") }
            composable(Tab.SONGWRITER.route) { NotBuiltYet("Songwriter") }
            composable(Tab.SETTINGS.route) { NotBuiltYet("Settings") }
        }
    }
}

@Composable
private fun NotBuiltYet(name: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text("$name is not built yet.", style = MaterialTheme.typography.bodyLarge)
    }
}
