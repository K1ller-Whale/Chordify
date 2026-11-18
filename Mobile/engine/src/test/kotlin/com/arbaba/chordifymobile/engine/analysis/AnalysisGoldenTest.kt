package com.arbaba.chordifymobile.engine.analysis

import com.arbaba.chordifymobile.engine.CancelledException
import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.acoustic.ChordBundle
import com.arbaba.chordifymobile.engine.assertJsonClose
import com.arbaba.chordifymobile.engine.double
import com.arbaba.chordifymobile.engine.has
import com.arbaba.chordifymobile.engine.measurements
import com.arbaba.chordifymobile.engine.model.toJson
import com.arbaba.chordifymobile.engine.obj
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.sourceInfo
import com.arbaba.chordifymobile.engine.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AnalysisGoldenTest {
    private val golden = Goldens.loadGz("analysis")
    private val analysisId = golden.str("analysis_id")!!
    private val inputs = golden.objects("inputs")
    private val analysed = inputs.filter { it.has("result") }

    private fun analyse(input: JsonObject, options: AnalysisOptions = AnalysisOptions()) =
        analyseMeasured(JVM_MODELS, input.measurements(), options, input.sourceInfo(analysisId))

    @Test
    fun theModelsBlockIsTheServers() {
        assertEquals(golden.obj("models").mapValues { it.value.jsonPrimitive.content }, JVM_MODELS.ids)
    }

    @Test
    fun theFirstDecodingAndTheKeyMatchPython() {
        assertEquals(7, analysed.size)
        for (input in analysed) {
            val name = input.str("file")!!
            val (segments, estimate) = firstPass(JVM_MODELS, input.measurements())
            val expected = input.objects("first_pass")
            assertEquals("$name segments", expected.size, segments.size)
            for ((want, got) in expected.zip(segments)) {
                assertEquals("$name start", want.double("start"), got.start, 1e-9)
                assertEquals("$name end", want.double("end"), got.end, 1e-9)
                assertEquals("$name label", want.str("label"), JVM_MODELS.vocabulary.decode(got.index))
                assertEquals("$name confidence", want.double("confidence"), got.confidence, 1e-9)
            }
            val (key, confidence) = estimate
            val wantKey = input.obj("key")
            assertEquals("$name key", "${wantKey.str("tonic")} ${wantKey.str("mode")}", key.name)
            assertEquals("$name key confidence", wantKey.double("confidence"), confidence, 1e-9)
        }
    }

    @Test
    fun resultsMatchPython() {
        for (input in analysed) {
            assertJsonClose(input.str("file")!!, input.getValue("result"), Json.parseToJsonElement(analyse(input).toJson()))
        }
    }

    @Test
    fun optionsChangeTheResultAsOnTheServer() {
        val loop = inputs.single { it.str("file") == "loop.wav" }
        for (replay in golden.objects("replays")) {
            val given = replay.obj("options")
            val options = AnalysisOptions(
                vocabulary = given["vocabulary"]?.jsonPrimitive?.content,
                predictions = given["predictions"]?.jsonPrimitive?.boolean ?: true,
                minSegmentBeats = given["min_segment_beats"]?.jsonPrimitive?.int ?: 1,
            )
            assertJsonClose("$given", replay.getValue("result"), Json.parseToJsonElement(analyse(loop, options).toJson()))
        }
    }

    @Test
    fun silenceFailsWithNoMusicDetected() {
        val silence = inputs.single { it.str("file") == "silence.wav" }
        val error = assertThrows(EngineException::class.java) { analyse(silence) }
        assertEquals(ErrorCode.valueOf(silence.obj("error").str("code")!!), error.code)
        assertEquals(silence.obj("error").str("message"), error.message)
    }

    @Test
    fun aFinerVocabularyThanTheModelKnowsIsRefused() {
        val text = repoFile("models/chordnet-chroma/3.0.0/bundle.json").readText()
        val majmin = Models(ChordBundle.parse(text.replace("\"vocabulary\": \"large\"", "\"vocabulary\": \"majmin\"")),
                            JVM_MODELS.lm, JVM_MODELS.lmId)
        val error = assertThrows(IllegalArgumentException::class.java) { targetVocabulary(majmin, AnalysisOptions("sevenths")) }
        assertEquals("the active chord model knows the 'majmin' chord types, not 'sevenths'", error.message)
    }

    @Test
    fun stagesAreReportedInOrder() {
        val stages = ArrayList<Pair<Stage, Double>>()
        val loop = analysed.first()
        analyseMeasured(JVM_MODELS, loop.measurements(), AnalysisOptions(), loop.sourceInfo(analysisId),
                        onProgress = { stage, fraction -> stages += stage to fraction })
        assertEquals(listOf(Stage.KEY to 0.7, Stage.DECODE_CHORDS to 0.8, Stage.ANALYSE to 0.9), stages)
    }

    @Test
    fun cancellingStopsTheAnalysis() {
        val loop = analysed.first()
        assertThrows(CancelledException::class.java) {
            analyseMeasured(JVM_MODELS, loop.measurements(), AnalysisOptions(), loop.sourceInfo(analysisId),
                            isCancelled = { true })
        }
    }
}
