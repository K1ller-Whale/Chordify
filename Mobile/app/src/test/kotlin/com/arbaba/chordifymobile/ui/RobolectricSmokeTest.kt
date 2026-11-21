package com.arbaba.chordifymobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.ui.analysis.GuitarDiagram
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Proves Compose renders under Robolectric and the shared helpers work. */
@RunWith(AndroidJUnit4::class)
class RobolectricSmokeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun rendersAGuitarDiagram() {
        rule.show(LANDSCAPE) { GuitarDiagram(listOf(-1, 3, 2, 0, 1, 0), 0) }
        rule.onAllNodes(SemanticsMatcher("description starts with Guitar chord diagram") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Guitar chord diagram") } == true
        }).assertCountEquals(1)
    }

    @Test fun namedControlsPassTheAccessibilityCheck() {
        rule.show(PORTRAIT) { Text("hello", Modifier.clickable {}) }
        rule.assertEveryControlNamed()
    }

    @Test fun loadsTheGoldenFixture() {
        assertEquals(listOf("C", "G", "Am", "F"), Fixtures.loop.chords.map { it.display }.take(4))
    }
}
