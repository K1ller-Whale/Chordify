package com.arbaba.chordifymobile.engine.model

import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.assertJsonClose
import com.arbaba.chordifymobile.engine.has
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisResultJsonTest {
    private val golden = Goldens.loadGz("analysis")

    private val results: List<Pair<String, JsonObject>> =
        golden.objects("inputs").filter { it.has("result") }.map { it.str("file")!! to it.getValue("result").jsonObject } +
            golden.objects("replays").map { "replay ${it.getValue("options")}" to it.getValue("result").jsonObject }

    @Test
    fun theServersResultsReadBackAsTheSameJson() {
        assertEquals(10, results.size)
        for ((name, json) in results) {
            val result = parseAnalysisResult(json.toString())
            assertJsonClose(name, json, Json.parseToJsonElement(result.toJson()), absolute = 0.0)
        }
    }

    @Test
    fun theServersSuggestionsReadBackAsTheSameJson() {
        for (case in golden.objects("next_chords").filter { it.has("response") }) {
            val json = case.getValue("response")
            val response = ServerJson.decodeFromJsonElement(ProgressionResponse.serializer(), json)
            assertJsonClose("${case.getValue("request")}", json,
                            ServerJson.encodeToJsonElement(ProgressionResponse.serializer(), response), absolute = 0.0)
        }
    }

    @Test
    fun defaultsAndNullsAreWritten() {
        val chord = ChordSegment(index = 0, start = 0.0, end = 1.0, label = "N", display = "N.C.", confidence = 0.9)
        val json = Json.parseToJsonElement(ServerJson.encodeToString(ChordSegment.serializer(), chord)).jsonObject
        assertEquals(listOf("index", "start", "end", "beats", "label", "display", "root", "quality", "bass", "roman",
                            "roman_triad", "function", "scale_hint", "confidence", "alternatives", "next", "theory_only",
                            "surprise"), json.keys.toList())
        assertEquals("null", json.getValue("surprise").toString())
        assertEquals("[]", json.getValue("next").toString())
    }
}
