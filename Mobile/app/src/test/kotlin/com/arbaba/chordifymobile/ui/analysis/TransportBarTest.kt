package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.ui.Fixtures
import com.arbaba.chordifymobile.ui.LANDSCAPE
import com.arbaba.chordifymobile.ui.PORTRAIT
import com.arbaba.chordifymobile.ui.assertEveryControlNamed
import com.arbaba.chordifymobile.ui.show
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransportBarTest {
    @get:Rule val rule = createComposeRule()

    private val time = mutableStateOf(2.0)
    private var toggles = 0
    private val seeks = mutableListOf<Double>()
    private val rates = mutableListOf<Double>()

    private fun showBar(playing: Boolean = false, size: androidx.compose.ui.unit.DpSize = LANDSCAPE) = rule.show(size) {
        TransportBar(Fixtures.loop, time, playing, 1.0, 4.0, { toggles++ }, { seeks += it }, { rates += it })
    }

    @Test fun timeReadsPositionAndDuration() {
        showBar()
        rule.onNodeWithTag("time").assertTextEquals("0:02 / 0:08")
    }

    @Test fun playButtonNamesItsActionAndToggles() {
        showBar(playing = false)
        rule.onNodeWithTag("play").assertContentDescriptionEquals("Play").performClick()
        assertEquals(1, toggles)
    }

    @Test fun playingShowsPause() {
        showBar(playing = true)
        rule.onNodeWithTag("play").assertContentDescriptionEquals("Pause")
    }

    @Test fun tappingTheSeekBarSeeks() {
        showBar()
        rule.onNodeWithTag("seekBar").assertContentDescriptionEquals("The whole song: tap or drag to jump")
            .performTouchInput { click(Offset(width * 0.75f, height / 2f)) }
        rule.waitForIdle()
        assertTrue(seeks.isNotEmpty())
        assertEquals(6.0, seeks.last(), 0.2)
    }

    @Test fun speedChipPicksARate() {
        showBar()
        rule.onNodeWithTag("speed").assertTextEquals("1× ▾").performClick()
        rule.onNodeWithText("0.75×").performClick()
        assertEquals(listOf(0.75), rates)
    }

    @Test fun everyControlIsNamed() {
        showBar()
        rule.assertEveryControlNamed()
    }

    @Test fun fitsPortraitToo() {
        showBar(size = PORTRAIT)
        rule.onNodeWithTag("seekBar").assertExists()
        rule.assertEveryControlNamed()
    }

    private var loops = 0

    private fun showLooping(looping: Boolean?, size: androidx.compose.ui.unit.DpSize = LANDSCAPE) = rule.show(size) {
        TransportBar(Fixtures.loop, time, false, 1.0, 4.0, { }, { }, { }, looping = looping, onLoop = { loops++ })
    }

    @Test fun loopChipIsHiddenWhenNotEditing() {
        showLooping(null)
        rule.onAllNodes(hasTestTag("loop")).assertCountEquals(0)
    }

    @Test fun loopChipCallsBackAndShowsNotSelected() {
        showLooping(false)
        rule.onNodeWithText("Loop chord").assertExists()
        rule.onNodeWithTag("loop").assertIsNotSelected().performClick()
        assertEquals(1, loops)
    }

    @Test fun loopChipShowsSelectedWhenLooping() {
        showLooping(true)
        rule.onNodeWithTag("loop").assertIsSelected()
    }

    @Test fun theSeekBarKeepsItsWidthWithTheLoopChipInPortrait() {
        showLooping(true, PORTRAIT)
        val width = rule.onNodeWithTag("seekBar").getUnclippedBoundsInRoot().let { it.right - it.left }
        assertTrue("seek bar is $width wide", width >= 120.dp)
        val chip = rule.onNodeWithTag("loop").getUnclippedBoundsInRoot()
        val play = rule.onNodeWithTag("play").getUnclippedBoundsInRoot()
        assertTrue("chip is above the main row", chip.bottom <= play.top + 1.dp)
    }

    @Test fun theLoopChipSharesTheRowWithTheSeekBarInLandscape() {
        showLooping(true, LANDSCAPE)
        val chip = rule.onNodeWithTag("loop").getUnclippedBoundsInRoot()
        val seek = rule.onNodeWithTag("seekBar").getUnclippedBoundsInRoot()
        assertTrue("chip $chip and seek bar $seek are on one row", chip.top < seek.bottom && seek.top < chip.bottom)
    }

    @Test fun loopChipFitsPortraitAndIsNamed() {
        showLooping(true, PORTRAIT)
        rule.onNodeWithTag("seekBar").assertExists()
        rule.onNodeWithTag("loop").assertExists()
        rule.assertEveryControlNamed()
    }
}
