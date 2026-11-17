package com.arbaba.chordifymobile.engine.theory

import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.assertClose
import com.arbaba.chordifymobile.engine.bool
import com.arbaba.chordifymobile.engine.double
import com.arbaba.chordifymobile.engine.doubles
import com.arbaba.chordifymobile.engine.has
import com.arbaba.chordifymobile.engine.int
import com.arbaba.chordifymobile.engine.ints
import com.arbaba.chordifymobile.engine.obj
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import com.arbaba.chordifymobile.engine.strings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TheoryGoldenTest {
    private val golden = Goldens.load("theory")

    private fun keyOf(row: JsonObject) = Key(row.int("tonic")!!, row.str("mode")!!)

    @Test
    fun keysMatchPython() {
        val expected = golden.objects("keys")
        assertEquals(expected.size, ALL_KEYS.size)
        for ((row, key) in expected.zip(ALL_KEYS)) {
            assertEquals(keyOf(row), key)
            assertEquals(key.name, row.bool("flats"), key.flats)
            assertEquals(key.name, row.str("tonic_name"), key.tonicName)
            assertEquals(row.str("name"), key.name)
            assertEquals(key.name, row.getValue("scale").ints(), key.scale)
        }
    }

    @Test
    fun keyNamesParseAsInPython() {
        for (row in golden.objects("parse_key")) {
            val text = row.str("text")!!
            if (row.has("error")) {
                assertThrows("'$text'", IllegalArgumentException::class.java) { parseKey(text) }
            } else {
                assertEquals("'$text'", keyOf(row.obj("value")), parseKey(text))
            }
        }
    }

    @Test
    fun everyChordIsDescribedAsInPython() {
        for (row in golden.objects("describe")) {
            val label = row.str("label")!!
            val key = keyOf(row)
            val at = "$label in ${key.name}"
            val description = describe(label, key)
            assertEquals(at, row.bool("diatonic"), isDiatonic(label, key))
            assertEquals(at, row.str("roman"), description.roman)
            assertEquals(at, row.str("roman_triad"), description.romanTriad)
            assertEquals(at, row.str("function"), description.function)
            assertEquals(at, row.str("scale_hint"), description.scaleHint)
            assertEquals(at, row.str("borrowed_from"), description.borrowedFrom)
            assertEquals(at, row.str("secondary"), description.secondary)
        }
    }

    @Test
    fun chordChangesAreExplainedAsInPython() {
        for (table in golden.objects("pairs")) {
            val key = keyOf(table)
            val labels = table.getValue("labels").strings().map { it!! }
            val reasons = table.getValue("reason").jsonArray
            val cadences = table.getValue("cadence").jsonArray
            for ((i, a) in labels.withIndex()) {
                val reasonRow = reasons[i].strings()
                val cadenceRow = cadences[i].strings()
                for ((j, b) in labels.withIndex()) {
                    assertEquals("$a → $b in ${key.name}", reasonRow[j], transitionReason(a, b, key))
                    assertEquals("$a → $b cadence in ${key.name}", cadenceRow[j], cadence(a, b, key))
                }
            }
        }
    }

    @Test
    fun keyProfilesScoreAsInPython() {
        for (case in golden.objects("key_scores")) {
            val scores = keyScores(case.getValue("chroma").doubles())
            val expected = case.getValue("scores").jsonArray.map { it.jsonArray }
            assertEquals(expected.size, scores.size)
            for ((row, actual) in expected.zip(scores)) {
                assertEquals(Key(row[0].jsonPrimitive.int, row[1].jsonPrimitive.content), actual.first)
                assertClose("score of ${actual.first.name}", row[2].jsonPrimitive.double, actual.second)
            }
        }
    }

    @Test
    fun keysAreEstimatedFromChordsAsInPython() {
        for (case in golden.objects("estimate_key")) {
            val chords = case.getValue("chords").jsonArray.map {
                val pair = it.jsonArray
                pair[0].jsonPrimitive.content to pair[1].jsonPrimitive.double
            }
            val (key, confidence) = estimateKeyFromChords(chords)
            assertEquals("$chords", keyOf(case), key)
            assertClose("confidence for $chords", case.double("confidence"), confidence)
        }
    }

    @Test
    fun loopsAreFoundAsInPython() {
        for (case in golden.objects("patterns")) {
            val numerals = case.getValue("numerals").strings()
            val lengths = case["lengths"]?.ints()?.map { it!! } ?: listOf(4, 3)
            val minCount = case["min_count"]?.jsonPrimitive?.int ?: 3
            val limit = case["limit"]?.jsonPrimitive?.int ?: 3
            val expected = case.getValue("result").jsonArray.map {
                val pattern = it.jsonObject
                ProgressionPattern(pattern.getValue("roman").strings().map { n -> n!! }, pattern.str("name"),
                                   pattern.int("count")!!)
            }
            assertEquals("$numerals", expected, findPatterns(numerals, lengths, minCount, limit))
        }
    }
}
