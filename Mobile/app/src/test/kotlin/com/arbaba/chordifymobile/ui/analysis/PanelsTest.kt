package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.ui.Fixtures
import com.arbaba.chordifymobile.ui.LANDSCAPE
import com.arbaba.chordifymobile.ui.PORTRAIT
import com.arbaba.chordifymobile.ui.show
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PanelsTest {
    @get:Rule val rule = createComposeRule()
    private val loop = Fixtures.loop

    @Test fun leadSheetShowsBarsAndSeeksOnTap() {
        var sought: Double? = null
        rule.show { Box(Modifier.size(400.dp, 300.dp)) { LeadSheet(loop, Notation.LETTERS, 0) { sought = it } } }
        rule.onNodeWithTag("bar0").assertIsDisplayed()
        rule.onNodeWithTag("bar1").performClick()
        assertEquals(loop.bars[1].start + 0.01, sought!!, 1e-9)
        assert(rule.onAllNodesWithText("LEAD SHEET").fetchSemanticsNodes().isEmpty())
    }

    @Test fun leadSheetMarksEditedChords() {
        val edited = loop.copy(chords = loop.chords.mapIndexed { i, ch -> if (i == 1) ch.copy(edited = true) else ch })
        rule.show { Box(Modifier.size(400.dp, 300.dp)) { LeadSheet(edited, Notation.LETTERS, 0) {} } }
        rule.onNodeWithText("G ✎").assertIsDisplayed()
        assertEquals(1, rule.onAllNodesWithText("✎", substring = true).fetchSemanticsNodes().size)
        rule.onNodeWithText("C").assertIsDisplayed()
        rule.onNodeWithText("Am").assertIsDisplayed()
    }

    @Test fun leadSheetWithoutBarsSaysSo() {
        rule.show(PORTRAIT) { LeadSheet(loop.copy(bars = emptyList()), Notation.LETTERS, -1) {} }
        rule.onNodeWithText("No steady beat was found, so there is no bar grid.").assertIsDisplayed()
    }

    @Test fun circleIsDescribed() {
        rule.show(LANDSCAPE) { CircleOfFifths(loop, loop.chords[0]) }
        rule.onNodeWithTag("circle").assertContentDescriptionEquals(circleDescription(loop))
    }

    @Test fun statsShowsBothHeadings() {
        rule.show(PORTRAIT) { ChordShare(loop, loop.chords.associateBy { it.display }) }
        rule.onNodeWithTag("statsPanel").assertIsDisplayed()
        rule.onNodeWithText("TIME PER CHORD").assertIsDisplayed()
        rule.onNodeWithText("REPEATING PROGRESSIONS").assertIsDisplayed()
    }
}
