package com.arbaba.chordifymobile.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import kotlin.math.abs

// Reading the golden files written by chordify_ai/export/mobile_goldens.py.

object Goldens {
    fun load(name: String): JsonObject {
        val stream = Goldens::class.java.getResourceAsStream("/goldens/$name.json")
            ?: error("goldens/$name.json is missing: run python -m chordify_ai.export.mobile_goldens")
        return Json.parseToJsonElement(stream.bufferedReader(Charsets.UTF_8).use { it.readText() }).jsonObject
    }
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

/** Floats agree to a relative 1e-9: numpy and Kotlin add in different orders, so the last bits differ. */
fun assertClose(message: String, expected: Double, actual: Double, relative: Double = 1e-9) {
    assertEquals(message, expected, actual, relative * maxOf(1.0, abs(expected)))
}
