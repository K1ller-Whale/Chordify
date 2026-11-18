package com.arbaba.chordifymobile.music

import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ServerJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class CapoGoldenTest {
    private val cases = WebGoldens.json.getValue("results").jsonArray.map { it.jsonObject }

    private fun result(case: JsonObject): AnalysisResult =
        ServerJson.decodeFromJsonElement(AnalysisResult.serializer(), case.getValue("result"))

    @Test
    fun theCapoSuggestionIsTheWebs() {
        assertEquals(3, cases.size)
        for (case in cases) {
            val chords = result(case).chords
            val name = case.getValue("file").jsonPrimitive.content
            val suggestion = case.getValue("suggestion").jsonObject
            assertEquals(name, CapoSuggestion(suggestion.getValue("capo").jsonPrimitive.int, suggestion.getValue("open").jsonPrimitive.double),
                         suggestCapo(chords))
            val shares = case.getValue("open_share").jsonArray.map { it.jsonPrimitive.double }
            for ((capo, share) in shares.withIndex()) assertEquals("$name capo $capo", share, openShare(chords, capo), 1e-12)
        }
    }

    @Test
    fun chordsAreNamedAsOnTheWebInBothNotations() {
        for (case in cases) {
            val names = case.getValue("names").jsonArray.map { it.jsonObject }
            for ((chord, name) in result(case).chords.zip(names)) {
                assertEquals(name.getValue("letters").jsonPrimitive.content, chordName(chord, Notation.LETTERS))
                assertEquals(name.getValue("roman").jsonPrimitive.content, chordName(chord, Notation.ROMAN))
            }
        }
    }

    @Test
    fun withACapoTheResultShowsTheShapesAsOnTheWeb() {
        for (case in cases) {
            for (row in case.getValue("with_capo").jsonArray.map { it.jsonObject }) {
                val capo = row.getValue("capo").jsonPrimitive.int
                val actual = ServerJson.encodeToJsonElement(AnalysisResult.serializer(), withCapo(result(case), capo))
                assertSameJson("${case.getValue("file")} capo $capo", row.getValue("result"), actual)
            }
        }
    }
}
