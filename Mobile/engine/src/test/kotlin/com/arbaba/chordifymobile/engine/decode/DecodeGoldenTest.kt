package com.arbaba.chordifymobile.engine.decode

import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.assertClose
import com.arbaba.chordifymobile.engine.double
import com.arbaba.chordifymobile.engine.doubles
import com.arbaba.chordifymobile.engine.int
import com.arbaba.chordifymobile.engine.ints
import com.arbaba.chordifymobile.engine.matrix
import com.arbaba.chordifymobile.engine.obj
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class DecodeGoldenTest {
    private val golden = Goldens.load("decode")

    @Test
    fun songsDecodeAsInPython() {
        for (case in golden.objects("decode")) {
            val name = case.str("name")!!
            val o = case.obj("options")
            val segments = decode(
                case.getValue("posteriors").matrix(), case.double("frame_rate"),
                beats = o["beats"]?.doubles(),
                changeProb = o["change_prob"]?.doubles(),
                prior = o["prior"]?.doubles(),
                alpha = o["alpha"]?.jsonPrimitive?.double ?: 0.5,
                selfProb = o["self_prob"]?.jsonPrimitive?.double,
                changeMatrix = o["change_matrix"]?.matrix(),
                subdivide = o["subdivide"]?.jsonPrimitive?.int ?: 1,
                minUnits = o["min_units"]?.jsonPrimitive?.int ?: 1,
                nAlternatives = o["n_alternatives"]?.jsonPrimitive?.int ?: 3,
                boundaryWeight = o["boundary_weight"]?.jsonPrimitive?.double ?: 0.5,
                frameOffset = o["frame_offset"]?.jsonPrimitive?.double,
                duration = o["duration"]?.jsonPrimitive?.double,
            )
            val expected = case.getValue("segments").jsonArray.map { it.jsonArray }
            assertEquals("$name: chords", expected.map { it[2].jsonPrimitive.int }, segments.map { it.index })
            for ((row, segment) in expected.zip(segments)) {
                assertClose("$name: start", row[0].jsonPrimitive.double, segment.start)
                assertClose("$name: end", row[1].jsonPrimitive.double, segment.end)
                assertClose("$name: confidence", row[3].jsonPrimitive.double, segment.confidence)
                val alternatives = row[4].jsonArray.map { it.jsonArray }
                assertEquals("$name: alternatives", alternatives.map { it[0].jsonPrimitive.int },
                             segment.alternatives.map { it.first })
                for ((alt, actual) in alternatives.zip(segment.alternatives)) {
                    assertClose("$name: alternative ${actual.first}", alt[1].jsonPrimitive.double, actual.second)
                }
            }
        }
    }

    @Test
    fun decodingUnitsMatchPython() {
        for (case in golden.objects("unit_boundaries")) {
            val beats = case.getValue("beats").let { if (it is JsonNull) null else it.doubles() }
            val result = unitBoundaries(case.double("duration"), beats, case.double("frame_rate"), case.int("subdivide")!!)
            val expected = case.getValue("result").doubles()
            assertEquals("boundaries for $case", expected.size, result.size)
            for (i in expected.indices) assertClose("boundary $i for $case", expected[i], result[i])
        }
    }

    @Test
    fun viterbiPathsMatchPython() {
        for (case in golden.objects("viterbi")) {
            val path = viterbi(case.getValue("log_emissions").matrix(), case.getValue("stay").doubles(),
                               case.getValue("change_matrix").matrix())
            assertEquals(case.getValue("path").ints(), path.toList())
        }
    }

    @Test
    fun stayProbabilitiesMatchPython() {
        for (case in golden.objects("combine_stay")) {
            assertClose("$case", case.double("result"),
                        combineStay(case.double("self_prob"), case.double("p_change"), case.double("weight")))
        }
    }

    @Test
    fun slashBassesMatchPython() {
        for (case in golden.objects("inversion")) {
            val label = case.str("label")!!
            assertEquals("$case", case.str("result"),
                         inversion(label, case.getValue("bass_probs").doubles(), case.double("threshold")))
        }
    }

    @Test
    fun windowMeansMatchPython() {
        val table = golden.obj("mean_over")
        val posteriors = table.getValue("posteriors").matrix()
        val times = table.getValue("frame_times").doubles()
        for (window in table.objects("windows")) {
            val expected = window.getValue("result").doubles()
            val actual = meanOver(posteriors, times, window.double("start"), window.double("end"))
            for (c in expected.indices) assertClose("$window [$c]", expected[c], actual[c])
        }
    }
}
