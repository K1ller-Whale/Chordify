package com.arbaba.chordifymobile.ui.library

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.data.Status
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
class LibraryScreenTest {
    @get:Rule val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun row(id: String, status: String = Status.COMPLETED) =
        Analysis(id, "$id.mp3", "file:///$id.mp3", false, "sha", 0L, status = status, modelFingerprint = "fp")

    private fun show(size: DpSize, analyses: List<Analysis> = emptyList(), recordingSeconds: Double? = null) {
        rule.show(size) {
            LibraryContent(
                analyses = analyses, recordingSeconds = recordingSeconds, message = null,
                onPick = { calls += "pick" }, onRecord = { calls += "record" }, onImportAnalysis = { calls += "import" },
                onDismissMessage = {}, onOpenAppSettings = null,
                onCancel = { calls += "cancel $it" }, onRetry = { calls += "retry $it" }, onDelete = { calls += "delete $it" },
                onOpen = { calls += "open $it" },
                onQuickChord = { calls += "quick" }, onSongwriter = { calls += "songwriter" }, onSettings = { calls += "settings" },
            )
        }
    }

    @Test fun emptyLibraryHintsAndAddOffersThreeChoices() {
        show(LANDSCAPE)
        rule.onNodeWithText("Pick a song or record", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("add").performClick()
        rule.onNodeWithText("Pick a song").performClick()
        rule.onNodeWithTag("add").performClick()
        rule.onNodeWithText("Record").performClick()
        rule.onNodeWithTag("add").performClick()
        rule.onNodeWithText("Import an analysis (.json)").performClick()
        assertEquals(listOf("pick", "record", "import"), calls)
    }

    @Test fun recordingShowsTheBarInsteadOfAdd() {
        show(LANDSCAPE, recordingSeconds = 42.0)
        rule.onNodeWithTag("recordingBar").assertIsDisplayed()
        rule.onNodeWithText("Recording 0:42").assertIsDisplayed()
        rule.onAllNodesWithText("Add").assertCountEquals(0)
        rule.onNodeWithTag("add").assertDoesNotExist()
        rule.onNodeWithText("Stop and analyse").performClick()
        assertEquals(listOf("record"), calls)
    }

    @Test fun rowsOpenCancelAndRetry() {
        show(LANDSCAPE, listOf(row("a"), row("b", Status.RUNNING)))
        rule.onNodeWithTag("analysis-a").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("open a", "cancel b"), calls)
    }

    @Test fun aFailedRowCanBeTriedAgain() {
        show(PORTRAIT, listOf(row("c", Status.FAILED)))
        rule.onNodeWithText("Try again").performClick()
        assertEquals(listOf("retry c"), calls)
    }

    @Test fun topBarActionsByTextInLandscape() {
        show(LANDSCAPE)
        rule.onNodeWithText("Quick chord").performClick()
        rule.onNodeWithText("Songwriter").performClick()
        rule.onNodeWithText("Settings").performClick()
        assertEquals(listOf("quick", "songwriter", "settings"), calls)
    }

    @Test fun topBarActionsByDescriptionInPortrait() {
        show(PORTRAIT)
        rule.onNodeWithContentDescription("Quick chord").performClick()
        rule.onNodeWithContentDescription("Songwriter").performClick()
        rule.onNodeWithContentDescription("Settings").performClick()
        assertEquals(listOf("quick", "songwriter", "settings"), calls)
    }

    @Test fun twoColumnsSideways() {
        show(LANDSCAPE, listOf(row("a"), row("b")))
        val a = rule.onNodeWithTag("analysis-a").getUnclippedBoundsInRoot()
        val b = rule.onNodeWithTag("analysis-b").getUnclippedBoundsInRoot()
        assertEquals(a.top, b.top)
        assertTrue(b.left > a.left)
    }

    @Test fun oneColumnUpright() {
        show(PORTRAIT, listOf(row("a"), row("b")))
        val a = rule.onNodeWithTag("analysis-a").getUnclippedBoundsInRoot()
        val b = rule.onNodeWithTag("analysis-b").getUnclippedBoundsInRoot()
        assertTrue(b.top > a.top)
    }

    @Test fun majorMinorSwitchIsGone() {
        show(PORTRAIT, listOf(row("a")))
        rule.onAllNodesWithText("Major and minor chords only (C instead of Cmaj7, G instead of G7)").assertCountEquals(0)
    }

    @Test fun everyControlIsNamed() {
        show(LANDSCAPE, listOf(row("a"), row("b", Status.RUNNING), row("c", Status.FAILED)))
        rule.assertEveryControlNamed()
    }

    @Test fun everyControlIsNamedInPortrait() {
        show(PORTRAIT, listOf(row("a")))
        rule.assertEveryControlNamed()
    }
}

