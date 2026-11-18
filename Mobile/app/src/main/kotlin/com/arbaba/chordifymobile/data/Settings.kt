package com.arbaba.chordifymobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.arbaba.chordifymobile.engine.analysis.AnalysisOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** The user's defaults, in DataStore (spec §6.1). M6's Settings screen adds the notation. */
class Settings(private val context: Context) {
    /** "Major and minor chords only": new analyses simplify to C and G instead of Cmaj7 and G7. */
    val majorMinorOnly: Flow<Boolean> = context.settingsStore.data.map { it[MAJOR_MINOR_ONLY] ?: false }

    suspend fun setMajorMinorOnly(value: Boolean) {
        context.settingsStore.edit { it[MAJOR_MINOR_ONLY] = value }
    }

    companion object {
        private val MAJOR_MINOR_ONLY = booleanPreferencesKey("major_minor_only")

        /** The options a new analysis is asked with, as the web app sends them. */
        fun options(majorMinorOnly: Boolean): AnalysisOptions =
            if (majorMinorOnly) AnalysisOptions(vocabulary = "majmin") else AnalysisOptions()
    }
}
