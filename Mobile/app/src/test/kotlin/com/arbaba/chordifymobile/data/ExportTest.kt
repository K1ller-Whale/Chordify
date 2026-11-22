package com.arbaba.chordifymobile.data

import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ServerJson
import com.arbaba.chordifymobile.engine.model.parseAnalysisResult
import com.arbaba.chordifymobile.engine.model.toJson
import com.arbaba.chordifymobile.music.WebGoldens
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportTest {
    private val loop: AnalysisResult = ServerJson.decodeFromJsonElement(
        AnalysisResult.serializer(), WebGoldens.json.getValue("results").jsonArray[0].jsonObject.getValue("result"))

    @Test
    fun numbersAreRoundedAsPythonRoundsThem() {
        // Python's f"{x:.3f}" rounds the exact binary value, half to even; String.format does not
        // (it gives 1.235, 0.125, 4.063 and 1.001 for these).
        assertEquals(listOf("1.234", "0.124", "4.062", "1.000", "2.001", "0.002", "10.000", "0.000"),
                     listOf(1.2345, 0.1245, 4.0625, 1.0005, 2.0005, 0.0015, 10.0, 0.0).map { fixed3(it) })
    }

    @Test
    fun theLabFileIsTheServers() {
        val chords = loop.chords.take(2).mapIndexed { i, c -> c.copy(start = 1.2345 + i, end = 4.0625 + i) }
        assertEquals("1.234\t4.062\t${chords[0].label}\n2.234\t5.062\t${chords[1].label}\n", labText(loop.copy(chords = chords)))
        assertEquals(loop.chords.size, labText(loop).lines().count { it.isNotEmpty() })
    }

    @Test
    fun theJsonFileIsTheResultDocument() {
        assertEquals(loop, parseAnalysisResult(jsonText(loop)))
    }

    @Test
    fun aJsonExportReadsBackAndAnythingElseIsRefused() {
        assertEquals(loop, parseExport(jsonText(loop)))
        for (text in listOf(labText(loop), "", "{}", "not json", jsonText(loop.copy(status = "failed")), jsonText(loop.copy(chords = emptyList())))) {
            val error = assertThrows(IllegalArgumentException::class.java) { parseExport(text) }
            assertEquals(NOT_AN_EXPORT, error.message)
        }
        assertTrue(isAnalysisExport("application/json", null))
        assertTrue(isAnalysisExport("application/octet-stream", "My song.JSON"))
        assertFalse(isAnalysisExport("audio/mpeg", "song.mp3"))
        assertFalse(isAnalysisExport(null, "song.lab"))
    }

    @Test
    fun theFileIsNamedAfterTheSong() {
        assertEquals("My song.lab", exportName("My song.mp3", ExportFormat.LAB))
        assertEquals("a_b.json", exportName("a/b.wav", ExportFormat.JSON))
        assertEquals("analysis.lab", exportName(".mp3", ExportFormat.LAB))
    }

    private val edits = ExportEdits(
        originalChords = loop.chords.map { it.copy(label = "N", display = "N.C.") },
        shapes = mapOf("G:maj" to listOf(3, -1, 0, 0, 3, 3)))
    private val editedLoop = loop.copy(chords = loop.chords.mapIndexed { i, c -> if (i == 0) c.copy(edited = true) else c })

    private fun edited(change: (JsonObject) -> JsonObject): String =
        ServerJson.encodeToString(JsonObject.serializer(), change(ServerJson.parseToJsonElement(jsonText(editedLoop, edits)).jsonObject))

    private fun withEdits(vararg entries: Pair<String, kotlinx.serialization.json.JsonElement>): String = edited { obj ->
        JsonObject(obj + ("edits" to JsonObject(obj.getValue("edits").jsonObject + entries)))
    }

    private fun gap(chords: List<com.arbaba.chordifymobile.engine.model.ChordSegment>) =
        chords.mapIndexed { i, c -> if (i == 1) c.copy(start = c.start + 0.5) else c }

    private fun damaged(text: String) {
        assertEquals(EDITS_DAMAGED, assertThrows(IllegalArgumentException::class.java) { readExport(text) }.message)
    }

    @Test
    fun anExportWithoutEditsIsByteIdenticalToToday() {
        assertEquals(loop.toJson(), jsonText(loop))
        assertEquals(loop.toJson(), jsonText(loop, null))
        assertFalse(jsonText(loop).contains("edits"))
        assertNull(readExport(jsonText(loop)).edits)
        assertEquals(loop, parseExport(jsonText(loop)))
    }

    @Test
    fun editsRideAlongAndReadBack() {
        val obj = ServerJson.parseToJsonElement(jsonText(editedLoop, edits)).jsonObject
        val e = obj.getValue("edits").jsonObject
        assertEquals(JsonPrimitive(1), e.getValue("version"))
        assertEquals(loop.chords.size, e.getValue("original_chords").jsonArray.size)
        assertEquals(JsonArray(listOf(3, -1, 0, 0, 3, 3).map { JsonPrimitive(it) }), e.getValue("shapes").jsonObject.getValue("G:maj"))
        val read = readExport(jsonText(editedLoop, edits))
        assertEquals(editedLoop, read.result)
        assertEquals(edits, read.edits)
        assertEquals(editedLoop, parseExport(jsonText(editedLoop, edits)))
    }

    @Test
    fun damagedEditsAreRefused() {
        damaged(withEdits("version" to JsonPrimitive(2)))
        damaged(edited { JsonObject(it + ("edits" to JsonPrimitive("oops"))) })
        damaged(withEdits("original_chords" to JsonArray(emptyList())))
        damaged(edited { JsonObject(it + ("edits" to JsonObject(it.getValue("edits").jsonObject - "original_chords"))) })
        damaged(withEdits("original_chords" to JsonArray(listOf(JsonPrimitive(1)))))
        damaged(withEdits("original_chords" to ServerJson.encodeToJsonElement(
            kotlinx.serialization.builtins.ListSerializer(com.arbaba.chordifymobile.engine.model.ChordSegment.serializer()), gap(edits.originalChords))))
        damaged(jsonText(editedLoop.copy(chords = gap(editedLoop.chords)), edits))
        damaged(jsonText(editedLoop, edits.copy(shapes = mapOf("G:maj" to listOf(3, -1, 0, 0, 3, 3, 3)))))
        damaged(jsonText(editedLoop, edits.copy(shapes = mapOf("G:maj" to listOf(3, -2, 0, 0, 3, 3)))))
        damaged(jsonText(editedLoop, edits.copy(shapes = mapOf("G:maj" to listOf(3, 25, 0, 0, 3, 3)))))
        damaged(withEdits("shapes" to buildJsonObject { put("G:maj", JsonPrimitive("x")) }))
    }

    @Test
    fun anOldExportWithAGapIsStillReadAsToday() {
        assertEquals(loop.chords.size, readExport(jsonText(loop.copy(chords = gap(loop.chords)))).result.chords.size)
    }

    @Test
    fun nonJsonIsNotAnExport() {
        assertEquals(NOT_AN_EXPORT, assertThrows(IllegalArgumentException::class.java) { readExport("not json") }.message)
        assertEquals(NOT_AN_EXPORT, assertThrows(IllegalArgumentException::class.java) { readExport("[1]") }.message)
    }

    @Test
    fun theLabFileListsAnEditedLabel() {
        val chords = loop.chords.mapIndexed { i, c -> if (i == 0) c.copy(label = "E:min", edited = true) else c }
        assertTrue(labText(loop.copy(chords = chords)).lines().first().endsWith("	E:min"))
    }
}
