package com.arbaba.chordifymobile.engine.lm

import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.assertClose
import com.arbaba.chordifymobile.engine.double
import com.arbaba.chordifymobile.engine.doubles
import com.arbaba.chordifymobile.engine.int
import com.arbaba.chordifymobile.engine.matrix
import com.arbaba.chordifymobile.engine.obj
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import com.arbaba.chordifymobile.engine.stringOrNull
import com.arbaba.chordifymobile.engine.strings
import com.arbaba.chordifymobile.engine.theory.Key
import com.arbaba.chordifymobile.engine.vocab.VOCABULARIES
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LmGoldenTest {
    private val golden = Goldens.load("lm")
    private val model = repoFile("models/progression-ngram/1.0.0/model.json.gz").inputStream()
        .use { NgramProgressionModel.load(it) }

    private fun keyOf(row: JsonObject) = Key(row.int("tonic")!!, row.str("mode")!!)

    @Test
    fun theShippedModelLoadsWithItsParameters() {
        val params = golden.obj("params")
        assertEquals(params.int("order"), model.order)
        assertEquals(params.int("min_count"), model.minCount)
        assertEquals(params.double("cache_weight"), model.cacheWeight, 0.0)
        assertEquals(params.int("max_context"), model.maxContext)
        assertEquals(golden.getValue("token_list").strings(), TOKENS)
    }

    @Test
    fun tokensAreKeyRelativeAsInPython() {
        for (row in golden.objects("tokens")) {
            val label = row.str("label")!!
            assertEquals("$label in ${keyOf(row).name}", row.str("token"), token(label, keyOf(row)))
        }
        for (row in golden.objects("token_labels")) {
            val tok = row.str("token")!!
            assertEquals("$tok in ${keyOf(row).name}", row.str("label"), tokenLabel(tok, keyOf(row)))
        }
    }

    @Test
    fun distributionsMatchPython() {
        for (case in golden.objects("cases")) {
            val tokens = case.getValue("tokens").strings().map { it!! }
            assertDistribution("corpus after $tokens", case.obj("corpus"), model.corpusDistribution(tokens))
            val (cache, context) = model.cacheCounts(tokens)
            assertEquals("cache after $tokens", case.obj("cache").mapValues { it.value.jsonPrimitive.int }, cache)
            assertEquals("cache context after $tokens", case.int("cache_context"), context)
            assertDistribution("mixed after $tokens", case.obj("mixed"), model.distribution(tokens).first)
            val top = model.top(tokens, case.int("k")!!)
            val expected = case.getValue("top").jsonArray.map { it.jsonArray }
            assertEquals("top after $tokens", expected.map { it[0].jsonPrimitive.content }, top.map { it.token })
            for ((row, candidate) in expected.zip(top)) {
                assertClose("p of ${candidate.token}", row[1].jsonPrimitive.double, candidate.p)
                assertEquals("cache count of ${candidate.token}", row[2].jsonPrimitive.int, candidate.cacheCount)
            }
        }
    }

    @Test
    fun predictionsMatchPython() {
        for (case in golden.objects("cases")) {
            val history = case.getValue("history").strings().map { it!! }
            val key = case.getValue("key").jsonArray.let { Key(it[0].jsonPrimitive.int, it[1].jsonPrimitive.content) }
            val beats = case.getValue("beats").let { if (it is JsonNull) null else it.doubles().toList() }
            val (predictions, prior) = predictNext(model, history, key, case.int("k")!!, beats)
            val expected = case.objects("predictions")
            assertEquals("$history", expected.size, predictions.size)
            for ((row, prediction) in expected.zip(predictions)) {
                val at = "$history -> ${prediction.label}"
                assertEquals(at, row.str("label"), prediction.label)
                assertEquals(at, row.str("display"), prediction.display)
                assertEquals(at, row["roman"]?.stringOrNull(), prediction.roman)
                assertClose(at, row.double("p"), prediction.p)
                assertEquals(at, row.str("reason"), prediction.reason)
                assertEquals(at, row.int("cache_count"), prediction.cacheCount)
                assertEquals(at, row["expected_beats"]?.jsonPrimitive?.double, prediction.expectedBeats)
            }
            val expectedPrior = case.objects("prior")
            assertEquals("prior after $history", expectedPrior.map { it.str("roman") }, prior.map { it.roman })
            for ((row, entry) in expectedPrior.zip(prior)) assertClose("prior ${entry.roman}", row.double("p"), entry.p)
        }
    }

    @Test
    fun changeMatricesMatchPython() {
        for (case in golden.objects("change_matrix")) {
            val vocabulary = VOCABULARIES.getValue(case.str("vocabulary")!!)
            val matrix = model.changeMatrix(vocabulary, keyOf(case))
            val expected = case.getValue("matrix").matrix()
            assertEquals(expected.size, matrix.size)
            for (i in expected.indices) {
                for (j in expected.indices) {
                    assertEquals("${vocabulary.name} [$i][$j]", expected[i][j], matrix[i][j], 1e-12)
                }
            }
        }
    }

    private fun assertDistribution(what: String, expected: JsonObject, actual: Map<String, Double>) {
        assertEquals(what, expected.keys.toList(), actual.keys.toList())
        for ((tok, p) in expected) assertClose("$what: $tok", p.jsonPrimitive.double, actual.getValue(tok))
    }
}

/** A file in the repository, found by walking up from the working directory (Mobile/engine under Gradle). */
private fun repoFile(path: String): File =
    generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, path) }.firstOrNull { it.exists() }
        ?: error("$path not found above ${System.getProperty("user.dir")}")
