package com.arbaba.chordifymobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.data.Status
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ServerJson
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.playback.PlaybackClock
import com.arbaba.chordifymobile.quick.QuickChordContent
import com.arbaba.chordifymobile.quick.QuickResult
import com.arbaba.chordifymobile.quick.QuickState
import com.arbaba.chordifymobile.songwriter.SongwriterContent
import com.arbaba.chordifymobile.ui.analysis.AnalysisContent
import com.arbaba.chordifymobile.ui.analysis.AudioFile
import com.arbaba.chordifymobile.ui.library.LibraryContent
import com.arbaba.chordifymobile.ui.settings.AboutContent
import com.arbaba.chordifymobile.ui.settings.SettingsContent
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.GZIPInputStream

/** Spec §6.3: every control a screen reader reaches says what it is, drawings are described, and
 *  the chord that is playing or found is announced. */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    private val loop: AnalysisResult by lazy {
        val stream = GZIPInputStream(javaClass.classLoader!!.getResourceAsStream("goldens/analysis.json.gz"))
        val golden = ServerJson.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
        val input = golden.getValue("inputs").jsonArray.map { it.jsonObject }.single { it.getValue("file").jsonPrimitive.content == "loop.wav" }
        ServerJson.decodeFromJsonElement(AnalysisResult.serializer(), input.getValue("result"))
    }

    private fun check(liveRegion: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { ChordifyTheme { content() } }
        val controls = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue("the screen has controls", controls.isNotEmpty())
        for (node in controls) {
            val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
            val description = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
            assertTrue("a control with no name: ${node.config}", (text + description).isNotBlank())
        }
        val announced = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).fetchSemanticsNodes()
        if (liveRegion) assertTrue("the result is announced", announced.isNotEmpty())
    }

    @Test
    fun theLibrary() {
        val row = Analysis("an_1", "song.mp3", "file:///song.mp3", false, "sha", 0L, modelFingerprint = "fp")
        check {
            LibraryContent(listOf(row.copy(status = Status.COMPLETED, duration = 8.0, keyName = "C major", bpm = 118.0),
                                  row.copy(id = "an_2", status = Status.RUNNING, stage = "acoustic", progress = 0.5),
                                  row.copy(id = "an_3", status = Status.FAILED, errorCode = "DECODE_FAILED", errorMessage = "No audio.")),
                           majorMinorOnly = false, recordingSeconds = null, message = null, onPick = {}, onRecord = {},
                           onMajorMinorOnly = {}, onDismissMessage = {}, onOpenAppSettings = null, onCancel = {}, onRetry = {},
                           onDelete = {}, onOpen = {})
        }
    }

    @Test
    fun theAnalysisScreen() {
        check(liveRegion = true) {
            AnalysisContent("loop.wav", loop, PlaybackClock(loop.source.duration), Notation.LETTERS, 0, AudioFile.MISSING, false,
                            onNotation = {}, onCapo = {}, onPickFile = {}, peaks = FloatArray(160) { 0.5f })
        }
        for (tag in listOf("timeline", "overview")) {
            val node = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag)).fetchSemanticsNodes().single()
            assertTrue("$tag is described", node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().isNotEmpty())
        }
    }

    @Test
    fun quickChord() = check(liveRegion = true) {
        QuickChordContent(QuickState.Idle, QuickResult("A:min7", "Am7", 0.9), null, onRecord = {}, onPick = {})
    }

    @Test
    fun theSongwriter() = check(liveRegion = true) {
        SongwriterContent(listOf("C", "G", "Am"), "", "", null, null, onRemove = {}, onDraft = {}, onAdd = {}, onKey = {},
                          onSuggest = {}, onPick = {})
    }

    @Test
    fun settingsAndAbout() {
        check { SettingsContent(true, onMajorMinorOnly = {}, storage = null, onAbout = {}) }
    }

    @Test
    fun about() = check { AboutContent("2.0.0", mapOf("chord" to "chordnet-chroma@3.0.0"), onSource = {}) }
}
