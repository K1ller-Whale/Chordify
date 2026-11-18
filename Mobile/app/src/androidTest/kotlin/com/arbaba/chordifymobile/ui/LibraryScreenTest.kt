package com.arbaba.chordifymobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.data.Status
import com.arbaba.chordifymobile.ui.library.LibraryContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = ArrayList<String>()

    private fun row(id: String, status: String, ownsFile: Boolean = false) =
        Analysis(id, "$id.mp3", "file:///$id.mp3", ownsFile, "sha", 0L, status = status, modelFingerprint = "fp")

    private fun show(analyses: List<Analysis>, recordingSeconds: Double? = null, message: String? = null) {
        compose.setContent {
            ChordifyTheme {
                LibraryContent(analyses, majorMinorOnly = false, recordingSeconds = recordingSeconds, message = message,
                               onPick = { events += "pick" }, onRecord = { events += "record" },
                               onMajorMinorOnly = { events += "majorMinorOnly=$it" }, onDismissMessage = { events += "dismiss" },
                               onOpenAppSettings = null, onCancel = { events += "cancel $it" },
                               onRetry = { events += "retry $it" }, onDelete = { events += "delete $it" },
                               onOpen = { events += "open $it" })
            }
        }
    }

    @Test
    fun anEmptyLibrarySaysWhatToDo() {
        show(emptyList())
        compose.onNodeWithText("Pick a file").performClick()
        compose.onNodeWithTag("record").performClick()
        compose.onNodeWithTag("majorMinorOnly").performClick()
        compose.onNodeWithText("Pick a song or record your instrument", substring = true).assertIsDisplayed()
        assertEquals(listOf("pick", "record", "majorMinorOnly=true"), events)
    }

    @Test
    fun eachStateShowsWhatTheWebAppShows() {
        show(listOf(
            row("running", Status.RUNNING).copy(stage = "decode_chords", progress = 0.8),
            row("done", Status.COMPLETED).copy(keyName = "C major", bpm = 117.5, duration = 8.0),
            row("failed", Status.FAILED).copy(errorCode = "NO_MUSIC_DETECTED", errorMessage = "101% of the audio has no recognisable chord."),
            row("cancelled", Status.CANCELLED),
        ))
        compose.onNodeWithText("Lining chords up with the beat…").assertIsDisplayed()
        compose.onNodeWithText("C major · 117.5 BPM · 0:08").assertIsDisplayed()
        compose.onNodeWithText("Could not analyse this audio").assertIsDisplayed()
        compose.onNodeWithText("101% of the audio has no recognisable chord.").assertIsDisplayed()
        compose.onNodeWithText("Error code: NO_MUSIC_DETECTED").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Analyse").performClick()
        compose.onNodeWithText("done.mp3").performClick()
        assertEquals(listOf("cancel running", "retry failed", "retry cancelled", "open done"), events)
    }

    @Test
    fun recordingShowsItsTime() {
        show(emptyList(), recordingSeconds = 72.4)
        compose.onNodeWithText("Stop and analyse (1:12)").assertIsDisplayed()
    }

    @Test
    fun swipingDeletesAnImportAtOnceAndARecordingAfterAsking() {
        show(listOf(row("song", Status.COMPLETED), row("take", Status.COMPLETED, ownsFile = true)))
        compose.onNodeWithTag("analysis-song").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(listOf("delete song"), events)
        compose.onNodeWithTag("analysis-take").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Delete this analysis?").assertIsDisplayed()
        compose.onNodeWithTag("confirmDelete").performClick()
        assertEquals(listOf("delete song", "delete take"), events)
    }
}
