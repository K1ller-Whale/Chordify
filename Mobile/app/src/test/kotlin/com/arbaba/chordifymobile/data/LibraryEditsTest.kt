package com.arbaba.chordifymobile.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.edit.ChordEdits
import com.arbaba.chordifymobile.edit.EditsLoad
import com.arbaba.chordifymobile.edit.spansOf
import com.arbaba.chordifymobile.engine.analysis.ChordSpan
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ChordSegment
import com.arbaba.chordifymobile.engine.model.ServerJson
import com.arbaba.chordifymobile.engine.model.parseAnalysisResult
import com.arbaba.chordifymobile.engine.model.toJson
import com.arbaba.chordifymobile.ui.Fixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LibraryEditsTest {
    private lateinit var context: Context
    private lateinit var db: LibraryDatabase
    private lateinit var library: Library

    private val noJobs = object : JobScheduler {
        override fun start(id: String) {}
        override fun cancel(id: String) {}
    }

    /** One segment per span, in the model's own result. */
    private val fakeRebuild: suspend (AnalysisResult, List<ChordSpan>) -> AnalysisResult = { result, spans ->
        val template = result.chords.first()
        result.copy(chords = spans.mapIndexed { i, s ->
            template.copy(index = i, start = s.start, end = s.end, label = s.label, display = s.label, edited = s.edited)
        })
    }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        library = Library(context, db.analyses(), noJobs, "fp", rebuild = fakeRebuild)
    }

    @After fun tearDown() {
        db.close()
        File(context.filesDir, "results").deleteRecursively()
    }

    private fun edits() = ChordEdits(
        chords = listOf(ChordSpan(0.0, 4.0, "E:min", true), ChordSpan(4.0, 8.0, "G:maj")),
        shapes = mapOf("G:maj" to listOf(3, -1, 0, 0, 3, 3)),
    )

    @Test fun savedEditsLoadBack() = runBlocking {
        library.saveEdits("a1", edits())
        assertEquals(EditsLoad.Loaded(edits()), library.edits("a1"))
        assertFalse(File(library.editsFile("a1").path + ".part").exists())
        assertEquals(listOf("a1.edits.json"), library.editsFile("a1").parentFile!!.list()!!.toList())
    }

    @Test fun noFileIsNone() = runBlocking {
        assertEquals(EditsLoad.None, library.edits("nothing"))
    }

    @Test fun garbageIsDamaged() = runBlocking {
        library.saveEdits("a1", edits())
        library.editsFile("a1").writeText("{ not json")
        assertEquals(EditsLoad.Damaged, library.edits("a1"))
    }

    @Test fun clearRemovesTheFile() = runBlocking {
        library.saveEdits("a1", edits())
        library.clearEdits("a1")
        assertFalse(library.editsFile("a1").exists())
        assertEquals(EditsLoad.None, library.edits("a1"))
    }

    @Test fun deleteRemovesTheEditsToo() = runBlocking {
        val id = library.importAnalysisText(Fixtures.loop.toJson()) { "x" }
        library.saveEdits(id, edits())
        assertTrue(library.editsFile(id).exists())
        library.delete(id)
        assertFalse(library.editsFile(id).exists())
        assertFalse(library.resultFile(id).exists())
    }

    @Test fun importWithoutEditsIsUnchanged() = runBlocking {
        val id = library.importAnalysisText(Fixtures.loop.toJson()) { "x" }
        assertEquals(Fixtures.loop.toJson(), library.resultFile(id).readText())
        assertFalse(library.editsFile(id).exists())
        assertEquals(Library.IMPORTED, library.get(id)!!.modelFingerprint)
    }

    @Ignore("needs X1's readExport")
    @Test fun importWithEditsKeepsTheModelsOriginalAndTheEdits() = runBlocking {
        val original = Fixtures.loop
        val shown = original.copy(chords = original.chords.mapIndexed { i, c ->
            if (i == 1) c.copy(label = "E:min", display = "Em", edited = true) else c
        })
        val shapes = mapOf("G:maj" to listOf(3, -1, 0, 0, 3, 3))
        val text = Json.parseToJsonElement(shown.toJson()).jsonObject.toMutableMap().also {
            it["edits"] = buildJsonObject {
                put("version", 1)
                put("original_chords", buildJsonArray {
                    original.chords.forEach { c -> add(ServerJson.encodeToJsonElement(ChordSegment.serializer(), c)) }
                })
                put("shapes", buildJsonObject {
                    shapes.forEach { (k, v) -> put(k, buildJsonArray { v.forEach { n -> add(JsonPrimitive(n)) } }) }
                })
            }
        }.let { JsonObject(it).toString() }

        val id = library.importAnalysisText(text) { "x" }

        val stored = parseAnalysisResult(library.resultFile(id).readText())
        val originalBase = original
        assertEquals(fakeRebuild(originalBase, spansOf(originalBase)).toJson(), stored.toJson())
        val loaded = library.edits(id) as EditsLoad.Loaded
        assertEquals(spansOf(shown), loaded.edits.chords)
        assertEquals(shapes, loaded.edits.shapes)
    }
}
