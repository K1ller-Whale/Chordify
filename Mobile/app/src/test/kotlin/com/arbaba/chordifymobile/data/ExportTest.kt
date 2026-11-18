package com.arbaba.chordifymobile.data

import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ServerJson
import com.arbaba.chordifymobile.engine.model.parseAnalysisResult
import com.arbaba.chordifymobile.music.WebGoldens
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
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
}
