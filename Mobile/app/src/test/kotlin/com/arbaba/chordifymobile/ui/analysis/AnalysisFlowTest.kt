package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.music.suggestCapo
import com.arbaba.chordifymobile.playback.PlaybackClock
import com.arbaba.chordifymobile.ui.ChordifyTheme
import com.arbaba.chordifymobile.ui.Fixtures
import com.arbaba.chordifymobile.ui.LANDSCAPE
import com.arbaba.chordifymobile.ui.PORTRAIT
import com.arbaba.chordifymobile.ui.show
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The whole analysis screen with the real components (M7 spec §4.1). */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AnalysisFlowTest {
    @get:Rule val rule = createComposeRule()

    private val loop = Fixtures.loop
    private val clock = PlaybackClock(loop.source.duration)

    /** The screen with the notation and the capo held in state, as the real route holds them. */
    private fun showScreen(size: DpSize = LANDSCAPE, audio: AudioFile = AudioFile.PLAYABLE) = rule.show(size) {
        var notation by remember { mutableStateOf(Notation.LETTERS) }
        var capo by remember { mutableStateOf(0) }
        AnalysisContent("Loop", loop, clock, notation, capo, audio, false,
            onNotation = { notation = it }, onCapo = { capo = it }, onPickFile = {})
    }

    private fun nowName() = rule.onNodeWithTag("nowName", useUnmergedTree = true)

    @Test fun theStageFollowsTheClock() {
        showScreen()
        nowName().assertTextEquals("C")
        rule.runOnIdle { clock.seek(loop.chords[1].start + 0.01) }
        rule.waitForIdle()
        nowName().assertTextEquals("G")
    }

    @Test fun theSuggestedCapoRenamesTheChordsAndSaysWhatSounds() {
        showScreen()
        rule.onNodeWithTag("capo").performClick()
        rule.onNodeWithText(capoMenuLabel(5, suggestCapo(loop.chords))).performClick()
        rule.waitForIdle()
        nowName().assertTextEquals("G")
        rule.onNodeWithTag("soundsAs", useUnmergedTree = true).assertTextEquals("Capo 5 · sounds as C")
    }

    @Test fun theMoreMenuSwitchesToRomanNumerals() {
        showScreen()
        rule.onNodeWithTag("more").performClick()
        rule.onNodeWithText("Show Roman numerals").performClick()
        rule.waitForIdle()
        nowName().assertTextEquals("I")
    }

    @Test fun eachTabShowsItsPanel() {
        showScreen()
        rule.onNodeWithTag("chordPanel").assertExists()
        val panels = listOf(PanelTab.SHEET to "leadSheet", PanelTab.CIRCLE to "circle",
                            PanelTab.STATS to "statsPanel", PanelTab.SONG to "songPanel", PanelTab.CHORD to "chordPanel")
        for ((tab, tag) in panels) {
            rule.onNodeWithText(tab.label).performClick()
            rule.onNodeWithTag(tag).assertExists()
        }
    }

    @Test fun aMissingFileShowsTheBanner() {
        showScreen(audio = AudioFile.MISSING)
        rule.onNodeWithTag("noAudio").assertExists()
        rule.onNodeWithText(FILE_MISSING).assertExists()
    }

    @Test fun aSwipeToTheLeftMovesToTheNextChord() {
        showScreen()
        rule.onNodeWithTag("stage").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(loop.chords[1].start + 0.01, clock.time.value, 1e-6) }
        nowName().assertTextEquals("G")
    }

    @Test fun aTapOnTheSeekBarJumpsThere() {
        showScreen()
        rule.onNodeWithTag("seekBar").performTouchInput { click(Offset(width * 0.75f, height / 2f)) }
        rule.runOnIdle { assertEquals(6.0, clock.time.value, 0.2) }
    }

    @Test fun portraitStacksTheSamePartsInOrder() {
        showScreen(PORTRAIT)
        val stage = rule.onNodeWithTag("stageArea").getUnclippedBoundsInRoot()
        val panel = rule.onNodeWithTag("panelArea").getUnclippedBoundsInRoot()
        assertTrue(panel.top >= stage.bottom)
        nowName().assertTextEquals("C")
        rule.onNodeWithTag("strip").assertExists()
        rule.onNodeWithTag("play").assertExists()
    }

    @Test fun theChosenTabSurvivesASizeChange() {
        var size by mutableStateOf(LANDSCAPE)
        rule.setContent {
            ChordifyTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                    AnalysisContent("Loop", loop, clock, Notation.LETTERS, 0, AudioFile.PLAYABLE, false,
                        onNotation = {}, onCapo = {}, onPickFile = {})
                }
            }
        }
        rule.onNodeWithText("Song").performClick()
        rule.onNodeWithTag("songPanel").assertExists()
        size = PORTRAIT
        rule.waitForIdle()
        rule.onNodeWithTag("songPanel").assertExists()
    }

    @Test fun thePortraitSeekBarIsWideEnoughToUse() {
        showScreen(PORTRAIT)
        val width = rule.onNodeWithTag("seekBar").getUnclippedBoundsInRoot().let { it.right - it.left }
        assertTrue("seek bar is $width wide", width >= 120.dp)
    }
}
