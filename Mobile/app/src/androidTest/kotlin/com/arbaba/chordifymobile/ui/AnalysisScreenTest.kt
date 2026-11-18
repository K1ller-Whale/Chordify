package com.arbaba.chordifymobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.data.Status
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ServerJson
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.playback.PlaybackClock
import com.arbaba.chordifymobile.ui.analysis.AnalysisContent
import com.arbaba.chordifymobile.ui.analysis.AnalysisStatus
import com.arbaba.chordifymobile.ui.analysis.AudioFile
import com.arbaba.chordifymobile.ui.analysis.FILE_MISMATCH
import com.arbaba.chordifymobile.ui.analysis.FILE_MISSING
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.GZIPInputStream

@RunWith(AndroidJUnit4::class)
class AnalysisScreenTest {
    @get:Rule
    val compose = createComposeRule()

    /** The server's analysis of loop.wav (C, G, Am, F in C major), from the engine's golden file. */
    private val loop: AnalysisResult by lazy {
        val stream = GZIPInputStream(javaClass.classLoader!!.getResourceAsStream("goldens/analysis.json.gz"))
        val golden = ServerJson.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
        val input = golden.getValue("inputs").jsonArray.map { it.jsonObject }.single { it.getValue("file").jsonPrimitive.content == "loop.wav" }
        ServerJson.decodeFromJsonElement(AnalysisResult.serializer(), input.getValue("result"))
    }
    private val events = ArrayList<String>()

    private fun show(clock: PlaybackClock = PlaybackClock(loop.source.duration), audio: AudioFile = AudioFile.PLAYABLE,
                     mismatch: Boolean = false): PlaybackClock {
        compose.setContent {
            var notation by mutableStateOf(Notation.LETTERS)
            var capo by mutableIntStateOf(0)
            ChordifyTheme {
                AnalysisContent("loop.wav", loop, clock, notation, capo, audio, mismatch,
                                onNotation = { notation = it }, onCapo = { capo = it; events += "capo $it" },
                                onPickFile = { events += "pick" })
            }
        }
        return clock
    }

    @Test
    fun theHeaderAndNowPlayingShowTheResult() {
        show()
        compose.onNodeWithText("Key C major").assertIsDisplayed()
        compose.onNodeWithText("118 BPM").assertIsDisplayed()
        compose.onNodeWithText("4 chords").assertIsDisplayed()
        compose.onNodeWithTag("nowName").assertTextEquals("C")
        compose.onNodeWithText("· Tonic in C major").assertIsDisplayed()
        compose.onNodeWithText(loop.chords[0].next[0].reason, substring = true).assertIsDisplayed()
        compose.onNodeWithTag("time").assertTextEquals("0:00 / 0:08")
    }

    @Test
    fun romanNumeralsReplaceTheNames() {
        show()
        compose.onNodeWithText("Roman").performClick()
        compose.onNodeWithTag("nowName").assertTextEquals("I")
    }

    @Test
    fun theChordFollowsTheClock() {
        val clock = show()
        compose.runOnIdle { clock.seek(loop.chords[1].start + 0.01) }
        compose.onNodeWithTag("nowName").assertTextEquals("G")
    }

    @Test
    fun theSuggestedCapoShowsTheShapesToPlay() {
        show()
        compose.onNodeWithText("With the capo on fret 5, 100% of the song is open chords.").assertIsDisplayed()
        compose.onNodeWithText("Use capo 5").performClick()
        compose.onNodeWithTag("nowName").assertTextEquals("G")
        compose.onNodeWithText("Capo 5 · sounds as C").assertIsDisplayed()
        compose.onNodeWithText("Capo on fret 5: chords are shown as the shapes you play, in G major. The song sounds in C major.")
            .assertIsDisplayed()
        compose.onNodeWithText("Key C major").assertIsDisplayed() // the header keeps the real key
        assertEquals(listOf("capo 5"), events)
    }

    @Test
    fun aMissingFileCanBePickedAgain() {
        show(audio = AudioFile.MISSING, mismatch = true)
        compose.onNodeWithText(FILE_MISSING).assertIsDisplayed()
        compose.onNodeWithText(FILE_MISMATCH).assertIsDisplayed()
        compose.onNodeWithText("Pick the file").performClick()
        assertEquals(listOf("pick"), events)
    }

    @Test
    fun anAnalysisWithoutAResultShowsItsState() {
        val row = Analysis("an_1", "song.mp3", "file:///song.mp3", false, "sha", 0L, modelFingerprint = "fp")
        var shown by mutableStateOf(row.copy(status = Status.RUNNING, stage = "acoustic", progress = 0.6))
        compose.setContent { ChordifyTheme { AnalysisStatus(shown, onRetry = { events += "retry" }) } }
        compose.onNodeWithText("Recognising chords…").assertIsDisplayed()
        shown = row.copy(status = Status.FAILED, errorCode = "DECODE_FAILED", errorMessage = "The file contains no audio samples.")
        compose.onNodeWithText("Could not analyse this audio").assertIsDisplayed()
        compose.onNodeWithText("The file contains no audio samples.").assertIsDisplayed()
        compose.onNodeWithText("Error code: DECODE_FAILED").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf("retry"), events)
    }
}
