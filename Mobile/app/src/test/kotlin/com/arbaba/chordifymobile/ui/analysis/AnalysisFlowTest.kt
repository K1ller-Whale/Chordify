package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.music.shapeKey
import com.arbaba.chordifymobile.music.suggestCapo
import com.arbaba.chordifymobile.playback.PlaybackClock
import com.arbaba.chordifymobile.ui.ChordifyTheme
import com.arbaba.chordifymobile.ui.Fixtures
import com.arbaba.chordifymobile.ui.LANDSCAPE
import com.arbaba.chordifymobile.ui.PORTRAIT
import com.arbaba.chordifymobile.ui.show
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import com.arbaba.chordifymobile.edit.ChordEdits
import com.arbaba.chordifymobile.edit.EditActions
import com.arbaba.chordifymobile.edit.EditController
import com.arbaba.chordifymobile.edit.spansOf
import com.arbaba.chordifymobile.engine.analysis.ChordSpan
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.Bar
import com.arbaba.chordifymobile.engine.vocab.displayName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The whole analysis screen with the real components (M7 spec §4.1). */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AnalysisFlowTest {
    @get:Rule val rule = createComposeRule()

    private val loop = Fixtures.loop
    private val clock = PlaybackClock(loop.source.duration)

    /** The screen with the notation and the capo held in state, as the real route holds them. */
    private fun showScreen(size: DpSize = LANDSCAPE, audio: AudioFile = AudioFile.PLAYABLE) = rule.show(size) {
        var notation by remember { mutableStateOf(Notation.LETTERS) }
        var capo by remember { mutableStateOf(0) }
        AnalysisContent("Loop", loop, clock, notation, capo, audio, false,
            onNotation = { notation = it }, onCapo = { capo = it }, onPickFile = {})
    }

    private fun nowName() = rule.onNodeWithTag("nowName", useUnmergedTree = true)

    @Test fun theStageFollowsTheClock() {
        showScreen()
        nowName().assertTextEquals("C")
        rule.runOnIdle { clock.seek(loop.chords[1].start + 0.01) }
        rule.waitForIdle()
        nowName().assertTextEquals("G")
    }

    @Test fun theSuggestedCapoRenamesTheChordsAndSaysWhatSounds() {
        showScreen()
        rule.onNodeWithTag("capo").performClick()
        rule.onNodeWithText(capoMenuLabel(5, suggestCapo(loop.chords))).performClick()
        rule.waitForIdle()
        nowName().assertTextEquals("G")
        rule.onNodeWithTag("soundsAs", useUnmergedTree = true).assertTextEquals("Capo 5 · sounds as C")
    }

    @Test fun theMoreMenuSwitchesToRomanNumerals() {
        showScreen()
        rule.onNodeWithTag("more").performClick()
        rule.onNodeWithText("Show Roman numerals").performClick()
        rule.waitForIdle()
        nowName().assertTextEquals("I")
    }

    @Test fun eachTabShowsItsPanel() {
        showScreen()
        rule.onNodeWithTag("chordPanel").assertExists()
        val panels = listOf(PanelTab.SHEET to "leadSheet", PanelTab.CIRCLE to "circle",
                            PanelTab.STATS to "statsPanel", PanelTab.SONG to "songPanel", PanelTab.CHORD to "chordPanel")
        for ((tab, tag) in panels) {
            rule.onNodeWithText(tab.label).performClick()
            rule.onNodeWithTag(tag).assertExists()
        }
    }

    @Test fun aMissingFileShowsTheBanner() {
        showScreen(audio = AudioFile.MISSING)
        rule.onNodeWithTag("noAudio").assertExists()
        rule.onNodeWithText(FILE_MISSING).assertExists()
    }

    @Test fun aSwipeToTheLeftMovesToTheNextChord() {
        showScreen()
        rule.onNodeWithTag("stage").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(loop.chords[1].start + 0.01, clock.time.value, 1e-6) }
        nowName().assertTextEquals("G")
    }

    @Test fun aTapOnTheSeekBarJumpsThere() {
        showScreen()
        rule.onNodeWithTag("seekBar").performTouchInput { click(Offset(width * 0.75f, height / 2f)) }
        rule.runOnIdle { assertEquals(6.0, clock.time.value, 0.2) }
    }

    @Test fun portraitStacksTheSamePartsInOrder() {
        showScreen(PORTRAIT)
        val stage = rule.onNodeWithTag("stageArea").getUnclippedBoundsInRoot()
        val panel = rule.onNodeWithTag("panelArea").getUnclippedBoundsInRoot()
        assertTrue(panel.top >= stage.bottom)
        nowName().assertTextEquals("C")
        rule.onNodeWithTag("strip").assertExists()
        rule.onNodeWithTag("play").assertExists()
    }

    @Test fun theChosenTabSurvivesASizeChange() {
        var size by mutableStateOf(LANDSCAPE)
        rule.setContent {
            ChordifyTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                    AnalysisContent("Loop", loop, clock, Notation.LETTERS, 0, AudioFile.PLAYABLE, false,
                        onNotation = {}, onCapo = {}, onPickFile = {})
                }
            }
        }
        rule.onNodeWithText("Song").performClick()
        rule.onNodeWithTag("songPanel").assertExists()
        size = PORTRAIT
        rule.waitForIdle()
        rule.onNodeWithTag("songPanel").assertExists()
    }

    @Test fun thePortraitSeekBarIsWideEnoughToUse() {
        showScreen(PORTRAIT)
        val width = rule.onNodeWithTag("seekBar").getUnclippedBoundsInRoot().let { it.right - it.left }
        assertTrue("seek bar is $width wide", width >= 120.dp)
    }

    // -- chord editing, end to end with a real EditController and a fake rebuild ------------------

    /** What AnalysisViewModel holds, with the engine's rebuild replaced by [fakeRebuild]. */
    private class EditHarness(val original: AnalysisResult) {
        val shown = mutableStateOf(original)
        val hasEdits = mutableStateOf(false)
        val saved = mutableListOf<ChordEdits>()
        val controller = EditController(
            duration = original.source.duration, original = original,
            rebuild = { spans -> fakeRebuild(original, spans) },
            save = { saved += it }, clear = {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            onFinished = { edits, result -> hasEdits.value = edits != null; shown.value = result ?: original },
        )
        val actions = object : EditActions {
            override fun startEditing() = controller.start(ChordEdits(chords = spansOf(original)))
            override fun cancelEditing() = controller.cancel()
            override fun done() = controller.done()
            override fun restore() = controller.restore()
            override fun select(index: Int?) = controller.select(index)
            override fun moveEdge(edge: Int, time: Double) = controller.moveEdge(edge, time)
            override fun setStart(index: Int, time: Double) = controller.setStart(index, time)
            override fun setEnd(index: Int, time: Double) = controller.setEnd(index, time)
            override fun split(index: Int, time: Double) = controller.split(index, time)
            override fun mergeWithNext(index: Int) = controller.mergeWithNext(index)
            override fun rename(index: Int, soundingLabel: String) = controller.rename(index, soundingLabel)
            override fun setShape(shapeLabel: String, shape: List<Int>?) = controller.setShape(shapeLabel, shape)
            override fun undo() = controller.undo()
            override fun redo() = controller.redo()
            override fun toggleLoop() = controller.toggleLoop()
            override suspend fun suggestions(index: Int): List<String> = emptyList()
        }
    }

    @Test fun editingAChordEndToEnd() {
        val harness = EditHarness(loop)
        rule.show(LANDSCAPE) {
            val edit = harness.controller.state.collectAsState().value
            AnalysisContent("Loop", harness.shown.value, clock, Notation.LETTERS, 0, AudioFile.PLAYABLE, false,
                onNotation = {}, onCapo = {}, onPickFile = {},
                hasEdits = harness.hasEdits.value, edit = edit, editActions = harness.actions)
        }
        val g = loop.chords[1]
        val middle = (g.start + g.end) / 2
        fun spans() = harness.controller.state.value!!.spans

        // enter editing and select chord 1 with a tap on its block
        rule.runOnIdle { clock.seek(middle) }
        rule.onNodeWithTag("edit").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("editHint").assertExists()
        rule.onNodeWithTag("strip").performTouchInput { click(Offset(width * PLAYHEAD, centerY)) }
        rule.waitForIdle()
        assertEquals(1, harness.controller.state.value!!.selected)
        rule.onNodeWithTag("editPanel").assertExists()
        nowName().assertTextEquals("G")

        // Change chord... -> E minor
        rule.onNodeWithText("Change chord…").performScrollTo().performClick()
        rule.onNodeWithTag("chordPicker").assertExists()
        rule.onNodeWithTag("root:E").performScrollTo().performClick()
        rule.onNodeWithTag("type:minor").performScrollTo().performClick()
        rule.onNodeWithText("Use Em").assertExists()
        rule.onNodeWithTag("pickerUse").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("chordPicker").assertDoesNotExist()
        nowName().assertTextEquals("Em")
        assertEquals("E:min", spans()[1].label)
        assertEquals("Em ✎", stripName(harness.controller.state.value!!.preview.chords[1], Notation.LETTERS))

        // drag the end of chord 1 earlier
        val end = spans()[1].end
        rule.onNodeWithTag("strip").performTouchInput {
            val pps = PIXELS_PER_SECOND.dp.toPx()
            down(Offset(width * PLAYHEAD + ((end - middle) * pps).toFloat(), centerY))
            moveBy(Offset(-35.dp.toPx(), 0f))
            up()
        }
        rule.waitForIdle()
        val moved = spans()[1].end
        assertTrue("edge moved from $end to $moved", moved < end - 0.2)
        assertTrue(spans()[1].edited && spans()[2].edited)
        rule.onNodeWithTag("undo").assertIsEnabled()

        // Undo takes the move back and keeps the rename
        rule.onNodeWithTag("undo").performClick()
        rule.waitForIdle()
        assertEquals(end, spans()[1].end, 1e-9)
        assertEquals("E:min", spans()[1].label)

        // Done saves, leaves editing, and the lead sheet marks the chord
        rule.onNodeWithTag("done").performClick()
        rule.waitForIdle()
        assertEquals(1, harness.saved.size)
        assertEquals("E:min", harness.saved[0].chords[1].label)
        assertEquals(null, harness.controller.state.value)
        rule.onNodeWithTag("edit").assertExists()
        assertEquals("Em", harness.shown.value.chords[1].display)
        rule.onNodeWithText(PanelTab.SHEET.label).performClick()
        rule.waitForIdle()
        val marked = rule.onAllNodes(hasText("Em ✎", substring = true)).fetchSemanticsNodes()
        assertTrue("the lead sheet marks Em", marked.isNotEmpty())
    }
}

/** One segment per span, with the display name and the bars' chord names redone (the engine's rebuild without the engine). */
private fun fakeRebuild(result: AnalysisResult, spans: List<ChordSpan>): AnalysisResult {
    val chords = spans.mapIndexed { i, s ->
        val same = result.chords.firstOrNull { it.label == s.label }
        (same ?: result.chords.first()).copy(index = i, start = s.start, end = s.end, label = s.label,
            display = displayName(s.label, shapeKey(result.key.global.tonic, result.key.global.mode, 0).flats), edited = s.edited)
    }
    val bars = result.bars.map { bar ->
        val quarter = (bar.end - bar.start) * 0.25
        Bar(bar.start, bar.end, chords.filter { minOf(it.end, bar.end) - maxOf(it.start, bar.start) >= quarter }
            .map { it.display }.fold(emptyList()) { acc, name -> if (acc.lastOrNull() == name) acc else acc + name })
    }
    return result.copy(chords = chords, bars = bars)
}
