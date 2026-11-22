package com.arbaba.chordifymobile.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.data.ScreenOrientation
import com.arbaba.chordifymobile.data.Status
import com.arbaba.chordifymobile.edit.EditUiState
import com.arbaba.chordifymobile.edit.spansOf
import com.arbaba.chordifymobile.ui.analysis.ChordPickerContent
import com.arbaba.chordifymobile.ui.analysis.ShapeEditorContent
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.playback.PlaybackClock
import com.arbaba.chordifymobile.quick.QuickChordContent
import com.arbaba.chordifymobile.quick.QuickResult
import com.arbaba.chordifymobile.quick.QuickState
import com.arbaba.chordifymobile.songwriter.SongwriterContent
import com.arbaba.chordifymobile.ui.analysis.AnalysisContent
import com.arbaba.chordifymobile.ui.analysis.AudioFile
import com.arbaba.chordifymobile.ui.library.LibraryContent
import com.arbaba.chordifymobile.ui.settings.AboutContent
import com.arbaba.chordifymobile.ui.settings.SettingsContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every control has a name a screen reader can say, and chord changes are announced (M7 spec §7). */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {
    @get:Rule val rule = createComposeRule()

    private fun analysis(size: DpSize) = rule.show(size) {
        val loop = Fixtures.loop
        AnalysisContent("Loop", loop, PlaybackClock(loop.source.duration), Notation.LETTERS, 0, AudioFile.PLAYABLE, false,
            onNotation = {}, onCapo = {}, onPickFile = {})
    }

    @Test fun libraryControlsAreNamed() {
        val row = Analysis("an_1", "song.mp3", "file:///song.mp3", false, "sha", 0L, status = Status.COMPLETED, modelFingerprint = "fp")
        rule.show {
            LibraryContent(listOf(row), null, null, {}, {}, {}, {}, null, {}, {}, {}, {}, {}, {}, {})
        }
        rule.assertEveryControlNamed()
    }

    @Test fun libraryControlsAreNamedInPortrait() {
        rule.show(PORTRAIT) {
            LibraryContent(emptyList(), 5.0, null, {}, {}, {}, {}, null, {}, {}, {}, {}, {}, {}, {})
        }
        rule.assertEveryControlNamed()
    }

    @Test fun analysisControlsAreNamedInLandscape() { analysis(LANDSCAPE); rule.assertEveryControlNamed() }

    @Test fun analysisControlsAreNamedInPortrait() { analysis(PORTRAIT); rule.assertEveryControlNamed() }

    private fun editing(size: DpSize, selected: Int?) = rule.show(size) {
        val loop = Fixtures.loop
        val state = EditUiState(spansOf(loop), emptyMap(), loop, selected, canUndo = true, canRedo = true, dirty = true,
            looping = false, saveError = "Could not save your changes: disk full")
        AnalysisContent("Loop", loop, PlaybackClock(loop.source.duration), Notation.LETTERS, 0, AudioFile.PLAYABLE, false,
            onNotation = {}, onCapo = {}, onPickFile = {}, hasEdits = true, edit = state)
    }

    @Test fun editingControlsAreNamedInLandscape() { editing(LANDSCAPE, 1); rule.assertEveryControlNamed() }

    @Test fun editingControlsAreNamedInPortrait() { editing(PORTRAIT, 1); rule.assertEveryControlNamed() }

    @Test fun editingWithNothingSelectedIsNamed() { editing(LANDSCAPE, null); rule.assertEveryControlNamed() }

    @Test fun theChordPickerControlsAreNamed() {
        rule.show(PORTRAIT) { ChordPickerContent("G:maj", listOf("E:min", "C:maj"), Fixtures.loop.key.global, false, {}, {}) }
        rule.assertEveryControlNamed()
    }

    @Test fun theShapeEditorControlsAreNamed() {
        rule.show(PORTRAIT) { ShapeEditorContent("G:maj", listOf(3, 2, 0, 0, 0, 3), listOf(3, 2, 0, 0, 0, 3), 0, {}, {}) }
        rule.assertEveryControlNamed()
    }

    @Test fun analysisAnnouncesTheChord() { analysis(LANDSCAPE); rule.assertAnnounced() }

    @Test fun quickChordControlsAreNamed() {
        rule.show { QuickChordContent(QuickState.Idle, QuickResult("A:min7", "Am7", 0.87), null, {}, {}, null, {}) }
        rule.assertEveryControlNamed()
    }

    @Test fun quickChordAnnouncesItsResult() {
        rule.show { QuickChordContent(QuickState.Idle, QuickResult("A:min7", "Am7", 0.87), null, {}, {}, null, {}) }
        rule.assertAnnounced()
    }

    @Test fun songwriterControlsAreNamed() {
        rule.show { SongwriterContent(listOf("C", "G"), "", "", null, null, {}, {}, {}, {}, {}, {}, {}) }
        rule.assertEveryControlNamed()
    }

    @Test fun settingsControlsAreNamed() {
        rule.show { SettingsContent(false, {}, ScreenOrientation.LANDSCAPE, {}, "2 analyses", {}, {}) }
        rule.assertEveryControlNamed()
    }

    @Test fun aboutControlsAreNamed() {
        rule.show { AboutContent("2.0.0", mapOf("chord" to "model-a"), {}, {}) }
        rule.assertEveryControlNamed()
    }
}
