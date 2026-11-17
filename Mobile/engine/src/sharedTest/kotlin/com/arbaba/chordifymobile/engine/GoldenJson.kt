package com.arbaba.chordifymobile.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.abs

// Reading the golden files written by chordify_ai/export/mobile_goldens.py. Shared by the JVM
// tests and the device tests; both find the files on the class path under /goldens.

object Goldens {
    fun load(name: String): JsonObject = parse(open("$name.json"))

    /** A gzip-compressed golden file, `<name>.json.gz`. */
    fun loadGz(name: String): JsonObject = parse(GZIPInputStream(open("$name.json.gz")))

    private fun open(file: String): InputStream =
        Goldens::class.java.getResourceAsStream("/goldens/$file")
            ?: error("goldens/$file is missing: run python -m chordify_ai.export.mobile_goldens (or tools/mobile_goldens.sh)")

    private fun parse(stream: InputStream): JsonObject =
        Json.parseToJsonElement(stream.bufferedReader(Charsets.UTF_8).use { it.readText() }).jsonObject
}

fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject
fun JsonObject.objects(name: String): List<JsonObject> = getValue(name).jsonArray.map { it.jsonObject }
fun JsonObject.has(name: String): Boolean = name in this
fun JsonObject.str(name: String): String? = getValue(name).stringOrNull()
fun JsonObject.int(name: String): Int? = getValue(name).let { if (it is JsonNull) null else it.jsonPrimitive.int }
fun JsonObject.double(name: String): Double = getValue(name).jsonPrimitive.double
fun JsonObject.bool(name: String): Boolean = getValue(name).jsonPrimitive.boolean

fun JsonElement.stringOrNull(): String? = if (this is JsonNull) null else jsonPrimitive.content
fun JsonElement.strings(): List<String?> = jsonArray.map { it.stringOrNull() }
fun JsonElement.ints(): List<Int?> = jsonArray.map { if (it is JsonNull) null else it.jsonPrimitive.int }
fun JsonElement.doubles(): DoubleArray = jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
fun JsonElement.matrix(): Array<DoubleArray> = jsonArray.map { it.doubles() }.toTypedArray()
fun JsonElement.floats(): FloatArray = jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
fun JsonElement.floatMatrix(): Array<FloatArray> = jsonArray.map { it.floats() }.toTypedArray()

/** Floats agree to a relative 1e-9: numpy and Kotlin add in different orders, so the last bits differ. */
fun assertClose(message: String, expected: Double, actual: Double, relative: Double = 1e-9) {
    assertEquals(message, expected, actual, relative * maxOf(1.0, abs(expected)))
}

/** Same shape, and every value within [absolute] of the expected one; a failure names the worst cell. */
fun assertMatrixClose(message: String, expected: Array<FloatArray>, actual: Array<FloatArray>, absolute: Double) {
    assertEquals("$message: frames", expected.size, actual.size)
    var worst = 0.0
    var where = "nowhere"
    for (t in expected.indices) {
        assertEquals("$message: values in frame $t", expected[t].size, actual[t].size)
        for (i in expected[t].indices) {
            val difference = abs(expected[t][i].toDouble() - actual[t][i])
            if (difference > worst) {
                worst = difference
                where = "frame $t, value $i: expected ${expected[t][i]}, got ${actual[t][i]}"
            }
        }
    }
    assertTrue("$message: largest difference $worst at $where (allowed $absolute)", worst <= absolute)
}

/** Raw features agree to 1e-4 of the song's largest raw value (spec §7.1). */
fun assertFeaturesClose(message: String, expected: Array<FloatArray>, actual: Array<FloatArray>) {
    val scale = maxOf(1.0, expected.maxOfOrNull { row -> row.maxOf { abs(it) }.toDouble() } ?: 0.0)
    assertMatrixClose(message, expected, actual, 1e-4 * scale)
}
