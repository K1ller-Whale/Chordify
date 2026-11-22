package com.arbaba.chordifymobile.engine.analysis

import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.assertJsonClose
import com.arbaba.chordifymobile.engine.has
import com.arbaba.chordifymobile.engine.measurements
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.toJson
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.sourceInfo
import com.arbaba.chordifymobile.engine.str
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RebuildTest {
    private val golden = Goldens.loadGz("analysis")
    private val analysisId = golden.str("analysis_id")!!
    private val analysed = golden.objects("inputs").filter { it.has("result") }
    private val loop = analysed.single { it.str("file") == "loop.wav" }

    private fun analyse(options: AnalysisOptions = AnalysisOptions()) =
        analyseMeasured(JVM_MODELS, loop.measurements(), options, loop.sourceInfo(analysisId))

    private fun spans(result: AnalysisResult) =
        result.chords.map { ChordSpan(it.start, it.end, it.label, it.edited) }

    private fun json(result: AnalysisResult) = Json.parseToJsonElement(result.toJson())

    @Test
    fun rebuildingTheUnchangedChordsReproducesEveryGolden() {
        assertEquals(7, analysed.size)
        for (input in analysed) {
            val result = analyseMeasured(JVM_MODELS, input.measurements(), AnalysisOptions(), input.sourceInfo(analysisId))
            val rebuilt = rebuild(result, spans(result), JVM_MODELS)
            assertTrue(rebuilt.chords.none { it.edited })
            // Rounded start/end/duration/beats feed the rebuild, so allow 1e-6 on every number.
            assertJsonClose(input.str("file")!!, json(result), json(rebuilt), 1e-6)
        }
    }

    @Test
    fun renamingAChordRecalculatesWhatDependsOnIt() {
        val result = analyse()
        val spans = spans(result).toMutableList()
        spans[1] = spans[1].copy(label = "E:min", edited = true)
        val rebuilt = rebuild(result, spans, JVM_MODELS)
        val before = result.chords[1]
        val after = rebuilt.chords[1]
        assertEquals("Em", after.display)
        assertNotEquals(before.display, after.display)
        assertNotEquals(before.roman, after.roman)
        assertNotEquals(before.function, after.function)
        assertEquals(1.0, after.confidence, 0.0)
        assertTrue(after.alternatives.isEmpty())
        assertTrue(after.edited)
        assertEquals("min", after.quality)
        assertNotEquals(result.bars, rebuilt.bars)
        assertNotEquals(result.summary.timeShare, rebuilt.summary.timeShare)
        assertNotEquals(result.chords[2].next, rebuilt.chords[2].next)
        assertEquals(result.chords[0].next, rebuilt.chords[0].next)
        assertFalse(rebuilt.chords[0].edited)
        assertEquals(result.chords[0].confidence, rebuilt.chords[0].confidence, 0.0)
        assertEquals(result.key, rebuilt.key)
        assertEquals(result.beats, rebuilt.beats)
        assertEquals(result.downbeats, rebuilt.downbeats)
    }

    @Test
    fun aChordOutsideTheTargetVocabularyStillGetsTheoryAndAQuality() {
        val result = analyse(AnalysisOptions(vocabulary = "majmin"))
        val spans = spans(result).toMutableList()
        spans[0] = spans[0].copy(label = "C:maj(9)", edited = true)
        val chord = rebuild(result, spans, JVM_MODELS).chords[0]
        assertEquals("I", chord.roman)
        assertEquals("maj", chord.quality)
    }

    @Test
    fun withoutPredictionsNextStaysEmpty() {
        val result = analyse(AnalysisOptions(predictions = false))
        val spans = spans(result).toMutableList()
        spans[1] = spans[1].copy(label = "E:min", edited = true)
        val rebuilt = rebuild(result, spans, JVM_MODELS)
        assertTrue(rebuilt.chords.all { it.next.isEmpty() && it.theoryOnly.isEmpty() && it.surprise == null })
        assertNull(rebuilt.summary.predictability)
    }

    @Test
    fun oneSegmentPerSpanInOrder() {
        val result = analyse()
        val first = result.chords.first()
        val mid = (first.start + first.end) / 2
        val spans = listOf(ChordSpan(first.start, mid, first.label, true), ChordSpan(mid, first.end, first.label, true)) +
            spans(result).drop(1)
        val rebuilt = rebuild(result, spans, JVM_MODELS)
        assertEquals(spans.size, rebuilt.chords.size)
        assertEquals(spans.indices.toList(), rebuilt.chords.map { it.index })
        assertEquals(spans.map { it.label }, rebuilt.chords.map { it.label })
    }

    @Test
    fun renamingAChordKeepsItsBeatCountBecauseTheTimesDidNotMove() {
        val pulse = analysed.single { it.str("file") == "pulse.wav" }
        val result = analyseMeasured(JVM_MODELS, pulse.measurements(), AnalysisOptions(), pulse.sourceInfo(analysisId))
        val spans = spans(result).toMutableList()
        assertEquals(9, result.chords[3].beats) // a recount from the rounded beats gives 8
        spans[3] = spans[3].copy(label = "A:min", edited = true)
        assertEquals(9, rebuild(result, spans, JVM_MODELS).chords[3].beats)
    }

    @Test
    fun anUneditedSpanWithAnotherLabelIsNotTheModelsChord() {
        val result = analyse()
        val spans = spans(result).toMutableList()
        spans[1] = spans[1].copy(label = "E:min")
        val chord = rebuild(result, spans, JVM_MODELS).chords[1]
        assertEquals(1.0, chord.confidence, 0.0)
        assertTrue(chord.alternatives.isEmpty())
        assertEquals("min", chord.quality)
        assertFalse(chord.edited)
    }

    @Test
    fun barsKeepTheirStoredBounds() {
        val result = analyse()
        val rebuilt = rebuild(result, spans(result), JVM_MODELS)
        assertEquals(result.bars.map { it.start to it.end }, rebuilt.bars.map { it.start to it.end })
    }
}
