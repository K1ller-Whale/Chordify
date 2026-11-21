package com.arbaba.chordifymobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    @Test fun anUnnamedClickableFailsWithAUsefulMessage() {
        rule.show(PORTRAIT) { Box(Modifier.size(40.dp).testTag("mystery").clickable {}) }
        val failure = runCatching { rule.assertEveryControlNamed() }.exceptionOrNull()
        assertNotNull("an unnamed clickable must fail the check", failure)
        assertTrue(failure!!.message, failure.message!!.contains("tag=mystery"))
        assertTrue(failure.message, failure.message!!.contains("bounds="))
    }

    @Test fun aLiveRegionIsAnnounced() {
        rule.show(PORTRAIT) { Text("now", Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        rule.assertAnnounced()
    }

    @Test fun noLiveRegionFailsAssertAnnounced() {
        rule.show(PORTRAIT) { Text("quiet") }
        assertNotNull(runCatching { rule.assertAnnounced() }.exceptionOrNull())
    }

    @Test fun forcedSizeTakesEffect() {
        var seen: DpSize? = null
        rule.show(PORTRAIT) {
            val density = LocalDensity.current
            Box(Modifier.fillMaxSize().onSizeChanged { seen = with(density) { DpSize(it.width.toDp(), it.height.toDp()) } })
        }
        rule.waitForIdle()
        // Whole pixels, so allow one pixel of rounding.
        assertEquals(PORTRAIT.width.value, seen!!.width.value, 1f)
        assertEquals(PORTRAIT.height.value, seen!!.height.value, 1f)
    }

    @Test fun loadsTheGoldenFixture() {
        assertEquals(listOf("C", "G", "Am", "F"), Fixtures.loop.chords.map { it.display }.take(4))
    }
}
