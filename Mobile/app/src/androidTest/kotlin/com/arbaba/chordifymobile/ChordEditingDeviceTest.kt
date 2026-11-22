package com.arbaba.chordifymobile

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbaba.chordifymobile.data.ExportEdits
import com.arbaba.chordifymobile.data.ExportFormat
import com.arbaba.chordifymobile.data.exportFile
import com.arbaba.chordifymobile.data.jsonText
import com.arbaba.chordifymobile.edit.EditsLoad
import com.arbaba.chordifymobile.edit.rebuildWith
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ServerJson
import com.arbaba.chordifymobile.ui.analysis.PIXELS_PER_SECOND
import com.arbaba.chordifymobile.ui.analysis.PLAYHEAD
import com.arbaba.chordifymobile.ui.analysis.PanelTab
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.GZIPInputStream

/** Correcting a chord through the real screen, with the real engine: the edit is kept across a relaunch,
 *  travels with the exported JSON, and Restore on the imported song brings the model's chord back. */
@RunWith(AndroidJUnit4::class)
class ChordEditingDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = (context.applicationContext as ChordifyApp).container
    private val library = container.library
    private val ids = ArrayList<String>()

    /** loop.wav's analysis (C G Am F), from the engine's goldens. */
    private val loop: AnalysisResult by lazy {
        val stream = GZIPInputStream(javaClass.classLoader!!.getResourceAsStream("goldens/analysis.json.gz"))
        val golden = ServerJson.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
        val input = golden.getValue("inputs").jsonArray.map { it.jsonObject }.single { it.getValue("file").jsonPrimitive.content == "loop.wav" }
        ServerJson.decodeFromJsonElement(AnalysisResult.serializer(), input.getValue("result"))
    }

    @After
    fun remove() = runBlocking<Unit> { ids.forEach { library.delete(it) } }

    private fun waitFor(tag: String, millis: Long = 60_000) = rule.waitUntil(millis) {
        rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForText(text: String, millis: Long = 60_000) = rule.waitUntil(millis) {
        rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitGone(tag: String, millis: Long = 60_000) = rule.waitUntil(millis) {
        rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()
    }

    private fun openSong(id: String) {
        waitFor("analysis-$id")
        rule.onNodeWithTag("analysis-$id").performClick()
        waitFor("edit")
    }

    private fun showSheet() {
        rule.onNodeWithText(PanelTab.SHEET.label).performClick()
        waitFor("leadSheet")
    }

    @Test
    fun aCorrectedChordIsKeptExportedAndRestored() = runBlocking<Unit> {
        val original = loop
        val chord = original.chords[1]
        val middle = (chord.start + chord.end) / 2
        val id = library.importAnalysisText(jsonText(original)) { "edit-source" }.also { ids += it }

        // edit chord 1 (G) into E minor through the screen, and press Done
        ActivityScenario.launch(MainActivity::class.java).use {
            openSong(id)
            rule.onNodeWithTag("edit").performClick()
            waitFor("editHint")

            rule.onNodeWithTag("strip").performTouchInput {
                click(Offset(width * PLAYHEAD + (middle * PIXELS_PER_SECOND.dp.toPx()).toFloat(), centerY))
            }
            waitFor("editPanel")
            rule.onNodeWithText("Change chord…").performScrollTo().performClick()
            waitFor("chordPicker")
            rule.onNodeWithTag("root:E").performScrollTo().performClick()
            rule.onNodeWithTag("type:minor").performScrollTo().performClick()
            rule.onNodeWithTag("pickerUse").performClick()
            waitGone("chordPicker")
            rule.waitUntil(30_000) { rule.onAllNodes(hasTestTag("nowName"), useUnmergedTree = true).fetchSemanticsNodes()
                .any { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == "Em" } }
            rule.onNodeWithTag("nowName", useUnmergedTree = true).assertTextEquals("Em")

            // drag the end of chord 1 earlier, wait for it to stay put, then undo the move
            val endBefore = endText()
            rule.onNodeWithTag("strip").performTouchInput {
                val pps = PIXELS_PER_SECOND.dp.toPx()
                down(Offset(width * PLAYHEAD + (chord.end * pps).toFloat(), centerY))
                moveBy(Offset(-35.dp.toPx(), 0f))
                up()
            }
            rule.waitUntil(30_000) { endText() != endBefore }
            rule.onNodeWithTag("undo").performClick()
            rule.waitUntil(30_000) { endText() == endBefore }

            rule.onNodeWithTag("done").performClick()
            waitGone("done")
        }

        val saved = (library.edits(id) as EditsLoad.Loaded).edits
        assertEquals(listOf("C:maj", "E:min", "A:min", "F:maj"), saved.chords.map { it.label })
        assertEquals(listOf(false, true, false, false), saved.chords.map { it.edited })
        assertEquals("G:maj", library.result(id)!!.chords[1].label)

        // a fresh launch shows the edit
        ActivityScenario.launch(MainActivity::class.java).use {
            openSong(id)
            showSheet()
            waitForText("Em ✎")
        }

        // export it as the screen does, and import it as a new song
        val shown = rebuildWith(container.engine, original, saved.chords)
        val file = exportFile(context, "edit-copy.wav", shown, ExportFormat.JSON, ExportEdits(original.chords, saved.shapes))
        val copy = library.importAnalysis(Uri.fromFile(file)).also { ids += it }
        val imported = (library.edits(copy) as EditsLoad.Loaded).edits
        assertEquals("E:min", imported.chords[1].label)
        assertTrue(imported.chords[1].edited)
        assertEquals("G:maj", library.result(copy)!!.chords[1].label)

        // Restore on the import gives the model's chord back
        ActivityScenario.launch(MainActivity::class.java).use {
            openSong(copy)
            showSheet()
            waitForText("Em ✎")
            rule.onNodeWithTag("more").performClick()
            rule.onNodeWithText("Restore the model's chords").performClick()
            rule.onNodeWithText("Restore").performClick()
            rule.waitUntil(30_000) { rule.onAllNodes(hasText("✎", substring = true)).fetchSemanticsNodes().isEmpty() }
            rule.waitUntil(30_000) { rule.onAllNodes(hasText("G", substring = false)).fetchSemanticsNodes().isNotEmpty() }
        }
        assertEquals(EditsLoad.None, library.edits(copy))
        assertTrue("the first song keeps its own edit", library.edits(id) is EditsLoad.Loaded)
    }

    private fun endText(): String? =
        rule.onAllNodes(hasTestTag("editEnd")).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
}
