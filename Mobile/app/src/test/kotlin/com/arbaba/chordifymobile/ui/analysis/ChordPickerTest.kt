package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.model.KeyInfo
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
class ChordPickerTest {
    @get:Rule val rule = createComposeRule()
    private val key = KeyInfo("C", "major")

    @Test fun labelsForEveryType() {
        val expected = mapOf("major" to "E:maj", "minor" to "E:min", "7" to "E:7", "maj7" to "E:maj7", "m7" to "E:min7",
            "6" to "E:maj6", "m6" to "E:min6", "sus2" to "E:sus2", "sus4" to "E:sus4", "dim" to "E:dim",
            "aug" to "E:aug", "9" to "E:9", "add9" to "E:maj(9)")
        expected.forEach { (type, label) -> assertEquals(type, label, pickerLabel("E", type, null)) }
        assertEquals("E:min/b3", pickerLabel("E", "minor", "G"))
        assertEquals("E:min", pickerLabel("E", "minor", "E"))
        assertEquals("B:maj", pickerLabel("B", "major", null))
    }

    private fun open(current: String, suggestions: List<String> = emptyList(), onPick: (String) -> Unit = {}) =
        rule.show(PORTRAIT) { ChordPickerContent(current, suggestions, key, false, onPick, {}) }

    @Test fun opensOnTheCurrentChord() {
        open("E:min")
        rule.onNodeWithTag("root:E").assertIsSelected()
        rule.onNodeWithTag("type:minor").assertIsSelected()
        rule.onNodeWithText("Use Em").assertExists()
    }

    @Test fun picksRootAndType() {
        var picked: String? = null
        open("E:min") { picked = it }
        rule.onNodeWithTag("root:G").performScrollTo().performClick()
        rule.onNodeWithTag("type:7").performScrollTo().performClick()
        rule.onNodeWithText("Use G7").assertExists()
        rule.onNodeWithTag("pickerPreview").assertExists()
        rule.onNodeWithTag("pickerUse").performClick()
        assertEquals("G:7", picked)
    }

    @Test fun suggestionPicksItsLabel() {
        var picked: String? = null
        open("C:maj", listOf("A:min", "F:maj")) { picked = it }
        rule.onNodeWithText("Suggested").assertExists()
        rule.onNodeWithTag("suggestion:A:min").performScrollTo().performClick()
        assertEquals("A:min", picked)
    }

    @Test fun noChordPicksN() {
        var picked: String? = null
        open("C:maj") { picked = it }
        rule.onNodeWithText("No chord (N.C.)").performScrollTo().performClick()
        rule.onNodeWithText("Use N.C.").assertExists()
        rule.onNodeWithTag("pickerUse").performClick()
        assertEquals("N", picked)
    }

    /** The preview and Use stay on screen, however long the choices are, so a pick never needs a scroll. */
    private fun useIsShownWithoutScrolling(size: DpSize) {
        rule.show(size) {
            ChordPickerContent("E:min", listOf("A:min", "F:maj", "G:maj", "C:maj", "D:min", "B:dim"), key, false, {}, {})
        }
        rule.onNodeWithTag("pickerUse").assertIsDisplayed()
        rule.onNodeWithTag("pickerPreview").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        val use = rule.onNodeWithTag("pickerUse").getUnclippedBoundsInRoot()
        assertTrue("Use ends at ${use.bottom}, the screen at ${size.height}", use.bottom <= size.height)
    }

    @Test fun useIsShownWithoutScrollingInLandscape() = useIsShownWithoutScrolling(LANDSCAPE)
    @Test fun useIsShownWithoutScrollingInPortrait() = useIsShownWithoutScrolling(PORTRAIT)

    @Test fun everyControlIsNamed() {
        open("E:min", listOf("A:min"))
        rule.onNodeWithTag("chordPicker").assertExists()
        rule.assertEveryControlNamed()
    }
}
