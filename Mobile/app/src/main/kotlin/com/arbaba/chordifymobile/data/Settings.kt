package com.arbaba.chordifymobile.data

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.arbaba.chordifymobile.engine.analysis.AnalysisOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** Settings > Screen (M7 spec §4.6). */
enum class ScreenOrientation(val label: String) {
    LANDSCAPE("Landscape"), PORTRAIT("Portrait"), FOLLOW_PHONE("Follow the phone");

    /** The value for Activity.requestedOrientation. */
    val activityOrientation: Int get() = when (this) {
        LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        FOLLOW_PHONE -> ActivityInfo.SCREEN_ORIENTATION_USER
    }
}

/** The user's defaults, in DataStore (spec §6.1). M6's Settings screen adds the notation. */
class Settings(private val context: Context) {
    /** "Major and minor chords only": new analyses simplify to C and G instead of Cmaj7 and G7. */
    val majorMinorOnly: Flow<Boolean> = context.settingsStore.data.map { it[MAJOR_MINOR_ONLY] ?: false }

    suspend fun setMajorMinorOnly(value: Boolean) {
        context.settingsStore.edit { it[MAJOR_MINOR_ONLY] = value }
    }

    /** How the app is turned: landscape unless the user chose otherwise. */
    val screenOrientation: Flow<ScreenOrientation> = context.settingsStore.data.map { prefs ->
        prefs[SCREEN_ORIENTATION]?.let { runCatching { ScreenOrientation.valueOf(it) }.getOrNull() } ?: ScreenOrientation.LANDSCAPE
    }

    suspend fun setScreenOrientation(value: ScreenOrientation) {
        context.settingsStore.edit { it[SCREEN_ORIENTATION] = value.name }
    }

    companion object {
        private val SCREEN_ORIENTATION = stringPreferencesKey("screen_orientation")
        private val MAJOR_MINOR_ONLY = booleanPreferencesKey("major_minor_only")

        /** The options a new analysis is asked with, as the web app sends them. */
        fun options(majorMinorOnly: Boolean): AnalysisOptions =
            if (majorMinorOnly) AnalysisOptions(vocabulary = "majmin") else AnalysisOptions()
    }
}
