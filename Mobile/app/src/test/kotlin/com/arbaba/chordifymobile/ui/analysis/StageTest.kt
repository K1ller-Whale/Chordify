package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.DpSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.model.Alternative
import com.arbaba.chordifymobile.engine.model.ChordSegment
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.ui.Fixtures
import com.arbaba.chordifymobile.ui.LANDSCAPE
import com.arbaba.chordifymobile.ui.PORTRAIT
import com.arbaba.chordifymobile.ui.assertAnnounced
import com.arbaba.chordifymobile.ui.assertEveryControlNamed
import com.arbaba.chordifymobile.ui.show
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StageTest {
    @get:Rule val rule = createComposeRule()

    private val c = Fixtures.loop.chords[0]
    private val unsureChord = c.copy(
        confidence = 0.4,
        alternatives = listOf(Alternative("A:min", "Am", 0.3), Alternative("E:min", "Em", 0.2)),
    )
    private fun diagrams() = rule.onAllNodes(SemanticsMatcher("diagram") {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith("Guitar chord diagram") } == true
    })

    private fun names(size: DpSize) {
        rule.show(size) { Stage(c, Notation.LETTERS, 0, null, {}) }
        rule.onNodeWithTag("nowName", useUnmergedTree = true).assertTextEquals("C")
        rule.onNodeWithTag("theoryLine", useUnmergedTree = true).assertTextEquals("I · Tonic")
        rule.onAllNodes(SemanticsMatcher("soundsAs") { it.config.getOrNull(SemanticsProperties.TestTag) == "soundsAs" }).assertCountEquals(0)
        diagrams().assertCountEquals(1)
        rule.assertEveryControlNamed()
        rule.assertAnnounced()
    }

    @Test fun namesLandscape() = names(LANDSCAPE)
    @Test fun namesPortrait() = names(PORTRAIT)

    @Test fun romanName() {
        rule.show { Stage(c, Notation.ROMAN, 0, null, {}) }
        rule.onNodeWithTag("nowName", useUnmergedTree = true).assertTextEquals("I")
    }

    private fun capo(size: DpSize) {
        rule.show(size) { Stage(c, Notation.LETTERS, 5, "C", {}) }
        rule.onNodeWithTag("soundsAs", useUnmergedTree = true).assertTextEquals("Capo 5 · sounds as C")
    }

    @Test fun capoLandscape() = capo(LANDSCAPE)
    @Test fun capoPortrait() = capo(PORTRAIT)

    private fun unsureBadge(size: DpSize) {
        rule.show(size) { Stage(unsureChord, Notation.LETTERS, 0, null, {}) }
        rule.onAllNodes(SemanticsMatcher("unsureText") { it.config.getOrNull(SemanticsProperties.TestTag) == "unsureText" }).assertCountEquals(0)
        rule.onNodeWithTag("unsure").performClick()
        rule.onNodeWithTag("unsureText").assertTextEquals(unsure(unsureChord)!!)
        rule.assertEveryControlNamed()
    }

    @Test fun unsureLandscape() = unsureBadge(LANDSCAPE)
    @Test fun unsurePortrait() = unsureBadge(PORTRAIT)

    @Test fun sureChordHasNoBadge() {
        rule.show { Stage(c.copy(confidence = 0.9, alternatives = unsureChord.alternatives), Notation.LETTERS, 0, null, {}) }
        rule.onAllNodes(SemanticsMatcher("unsure") { it.config.getOrNull(SemanticsProperties.TestTag) == "unsure" }).assertCountEquals(0)
    }

    private fun noChord(size: DpSize) {
        val nc = Fixtures.sevenths.chords[4]
        rule.show(size) { Stage(nc, Notation.LETTERS, 2, "C", {}) }
        rule.onNodeWithTag("nowName", useUnmergedTree = true).assertTextEquals("N.C.")
        rule.onNodeWithTag("theoryLine", useUnmergedTree = true).assertTextEquals("No chord")
        diagrams().assertCountEquals(0)
    }

    @Test fun noChordLandscape() = noChord(LANDSCAPE)
    @Test fun noChordPortrait() = noChord(PORTRAIT)

    private fun swipes(size: DpSize) {
        val got = mutableListOf<Int>()
        rule.show(size) { Stage(c, Notation.LETTERS, 0, null, { got += it }) }
        rule.onNodeWithTag("stage").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("stage").performTouchInput { swipeRight() }
        assertEquals(listOf(1, -1), got)
    }

    @Test fun swipeLandscape() = swipes(LANDSCAPE)
    @Test fun swipePortrait() = swipes(PORTRAIT)

    @Test fun announcesOnlyTheChordAndItsLines() {
        rule.show { Stage(unsureChord, Notation.LETTERS, 5, "C", {}) }
        rule.onNodeWithTag("unsure").performClick()
        val live = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).fetchSemanticsNodes()
        assertEquals(1, live.size)
        val spoken = live[0].config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
        assertTrue(spoken, "C" in spoken && "I \u00b7 Tonic" in spoken && "Capo 5 \u00b7 sounds as C" in spoken)
        assertTrue(spoken, "Unsure" !in spoken && "Guitar" !in spoken)
        assertTrue(live[0].config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().none { it.startsWith("Guitar") })
    }
}
