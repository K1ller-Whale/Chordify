package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.DpSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.data.Status
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.playback.PlaybackClock
import com.arbaba.chordifymobile.ui.Fixtures
import com.arbaba.chordifymobile.ui.LANDSCAPE
import com.arbaba.chordifymobile.ui.PORTRAIT
import com.arbaba.chordifymobile.ui.show
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AnalysisScreenTest {
    @get:Rule val rule = createComposeRule()

    private val loop = Fixtures.loop
    private val clock = PlaybackClock(loop.source.duration)
    private var notations = mutableListOf<Notation>()

    private fun showScreen(size: DpSize, audio: AudioFile = AudioFile.PLAYABLE, capo: Int = 0) = rule.show(size) {
        AnalysisContent("Loop", loop, clock, Notation.LETTERS, capo, audio, false,
            onNotation = { notations += it }, onCapo = {}, onPickFile = {})
    }

    /** Every tab's label is laid out whole on one line (real fonts: graphicsMode=NATIVE). */
    private fun tabLabelsFit(size: DpSize) {
        showScreen(size)
        val inATab = hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        for (tab in PanelTab.entries) {
            val label = rule.onNode(hasText(tab.label) and inATab, useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            label.config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
            val layout = layouts.single()
            val natural = layout.multiParagraph.intrinsics.maxIntrinsicWidth
            val room = layout.layoutInput.constraints.maxWidth
            assertTrue("\"${tab.label}\" needs $natural px at $size, it has $room px", natural <= room)
            assertFalse("\"${tab.label}\" is cut to one line at $size", layout.multiParagraph.didExceedMaxLines)
        }
    }

    @Test fun tabLabelsFitALandscapePhone() = tabLabelsFit(LANDSCAPE)
    @Test fun tabLabelsFitANarrowerLandscapePhone() = tabLabelsFit(DpSize(730.dp, 360.dp))
    @Test fun tabLabelsFitAPortraitPhone() = tabLabelsFit(PORTRAIT)

    @Test fun theNoAudioBannerSitsAboveTheStageNotOverIt() {
        showScreen(DpSize(730.dp, 336.dp), audio = AudioFile.MISSING, capo = 2) // a 360 dp-tall phone, less its status bar
        val banner = rule.onNodeWithTag("noAudio").getUnclippedBoundsInRoot()
        val stage = rule.onNodeWithTag("stage").getUnclippedBoundsInRoot()
        assertTrue("banner ${banner.bottom} must end before the stage ${stage.top}", banner.bottom <= stage.top)
        for (tag in listOf("nowName", "theoryLine", "soundsAs")) {
            val line = rule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("$tag $line must be inside the stage $stage", line.top >= stage.top && line.bottom <= stage.bottom)
        }
        val diagram = rule.onNode(hasContentDescription("Guitar chord diagram", substring = true)).getUnclippedBoundsInRoot()
        assertTrue("diagram $diagram must be inside the stage $stage", diagram.top >= stage.top && diagram.bottom <= stage.bottom)
    }

    @Test fun landscapePutsThePanelBesideTheStage() {
        showScreen(LANDSCAPE)
        val stage = rule.onNodeWithTag("stageArea").getUnclippedBoundsInRoot()
        val panel = rule.onNodeWithTag("panelArea").getUnclippedBoundsInRoot()
        assertEquals(stage.top, panel.top)
        assertTrue("panel ${panel.left} must start at or after stage ${stage.right}", panel.left >= stage.right)
    }

    @Test fun portraitStacksThePanelUnderTheStage() {
        showScreen(PORTRAIT)
        val stage = rule.onNodeWithTag("stageArea").getUnclippedBoundsInRoot()
        val panel = rule.onNodeWithTag("panelArea").getUnclippedBoundsInRoot()
        assertTrue("panel ${panel.top} must start at or after stage ${stage.bottom}", panel.top >= stage.bottom)
    }

    @Test fun tabsSwitchThePanel() {
        showScreen(LANDSCAPE)
        for (tab in PanelTab.entries) rule.onNodeWithText(tab.label).assertExists()
        rule.onNodeWithText("Chord").assertIsSelected()
        rule.onNodeWithText("Song").performClick()
        rule.onNodeWithText("Song").assertIsSelected()
    }

    @Test fun theArrowJumpsToTheNextChord() {
        showScreen(LANDSCAPE)
        rule.onNodeWithTag("analysis").performKeyInput { pressKey(Key.DirectionRight) }
        rule.runOnIdle { assertEquals(loop.chords[1].start + 0.01, clock.time.value, 1e-6) }
    }

    @Test fun rTogglesTheNotation() {
        showScreen(LANDSCAPE)
        rule.onNodeWithTag("analysis").performKeyInput { pressKey(Key.R) }
        rule.runOnIdle { assertEquals(listOf(Notation.ROMAN), notations) }
    }

    @Test fun spaceStartsAndStopsThePlayback() {
        rule.mainClock.autoAdvance = false
        showScreen(LANDSCAPE)
        rule.onNodeWithTag("analysis").performKeyInput { pressKey(Key.Spacebar) }
        rule.runOnIdle { assertTrue(clock.playing.value) }
        rule.onNodeWithTag("analysis").performKeyInput { pressKey(Key.Spacebar) }
        rule.runOnIdle { assertFalse(clock.playing.value) }
    }

    @Test fun aFailedAnalysisOffersToTryAgain() {
        val row = Analysis("an_1", "song.mp3", "file:///song.mp3", false, "00", 0L, status = Status.FAILED,
            errorCode = "decode", errorMessage = "bad", modelFingerprint = "f")
        rule.show { AnalysisStatus(row, onRetry = {}) }
        rule.onNodeWithText("Could not analyse this audio").assertExists()
        rule.onNodeWithText("Try again").assertExists()
    }
}
