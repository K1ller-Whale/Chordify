package com.arbaba.chordifymobile.engine.analysis

import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.assertJsonClose
import com.arbaba.chordifymobile.engine.double
import com.arbaba.chordifymobile.engine.doubles
import com.arbaba.chordifymobile.engine.floatMatrix
import com.arbaba.chordifymobile.engine.has
import com.arbaba.chordifymobile.engine.model.ProgressionResponse
import com.arbaba.chordifymobile.engine.model.ServerJson
import com.arbaba.chordifymobile.engine.obj
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import com.arbaba.chordifymobile.engine.strings
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SuggestGoldenTest {
    private val golden = Goldens.loadGz("analysis")

    @Test
    fun nextChordsAnswersAsTheServer() {
        val cases = golden.objects("next_chords")
        assertEquals(11, cases.size)
        for (case in cases) {
            val request = case.obj("request")
            val call = {
                nextChords(JVM_MODELS, request.getValue("chords").strings().map { it!! },
                           key = request["key"]?.jsonPrimitive?.content, k = request["k"]?.jsonPrimitive?.int ?: 3,
                           durationsBeats = request["durations_beats"]?.doubles()?.toList())
            }
            if (case.has("error")) {
                val error = assertThrows(IllegalArgumentException::class.java) { call() }
                assertEquals("$request", case.obj("error").str("message"), error.message)
            } else {
                assertJsonClose("$request", case.getValue("response"),
                                ServerJson.encodeToJsonElement(ProgressionResponse.serializer(), call()))
            }
        }
    }

    @Test
    fun singleChordNamesEachClipAsTheServer() {
        for (clip in golden.objects("quick_chord")) {
            val (label, confidence) = singleChord(clip.getValue("chord").floatMatrix(), JVM_MODELS.vocabulary)
            val name = "${clip.str("file")} at ${clip.double("start")} s"
            assertEquals(name, clip.str("label"), label)
            assertEquals(name, clip.double("confidence"), confidence, 1e-5)
        }
    }
}
