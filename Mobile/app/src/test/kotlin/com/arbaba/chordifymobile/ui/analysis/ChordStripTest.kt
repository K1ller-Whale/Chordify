package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.ui.Fixtures
import com.arbaba.chordifymobile.ui.LANDSCAPE
import com.arbaba.chordifymobile.ui.assertEveryControlNamed
import com.arbaba.chordifymobile.ui.show
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChordStripTest {
    @get:Rule val rule = createComposeRule()
    private val time = mutableStateOf(0.0)
    private val seeks = mutableListOf<Double>()

    // In a Box, so the strip takes its own height rather than the screen's.
    private fun showStrip() = rule.show(LANDSCAPE) {
        Box(Modifier.fillMaxSize()) { ChordStrip(Fixtures.loop, time, 0, Notation.LETTERS, null, setOf(1), { seeks += it }) }
    }

    @Test fun describesItself() {
        showStrip()
        rule.onNodeWithTag("strip")
            .assertContentDescriptionEquals("Timeline of 4 chords: tap or drag to move through the song")
    }

    @Test fun is72DpTall() {
        showStrip()
        rule.onNodeWithTag("strip").assertHeightIsEqualTo(72.dp)
    }

    @Test fun tapSeeks() {
        showStrip()
        rule.onNodeWithTag("strip").performTouchInput {
            click(Offset(width * 0.33f + 70.dp.toPx(), centerY))
        }
        assertEquals(1, seeks.size)
        assertEquals(1.0, seeks[0], 0.1)
    }

    @Test fun dragSeeks() {
        showStrip()
        rule.onNodeWithTag("strip").performTouchInput { swipeLeft() }
        assertTrue(seeks.isNotEmpty() && seeks.last() > 0)
    }

    @Test fun controlsAreNamed() {
        showStrip()
        rule.assertEveryControlNamed()
    }
}
