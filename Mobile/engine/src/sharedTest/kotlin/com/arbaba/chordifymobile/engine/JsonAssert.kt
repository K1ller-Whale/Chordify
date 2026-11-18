package com.arbaba.chordifymobile.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import kotlin.math.abs

/** Two JSON documents agree: the same keys (in any order), lists, strings, booleans and nulls,
 *  and every number within [absolute]. */
fun assertJsonClose(message: String, expected: JsonElement, actual: JsonElement, absolute: Double = 1e-9) {
    fun walk(path: String, e: JsonElement, a: JsonElement) {
        when (e) {
            is JsonObject -> {
                if (a !is JsonObject) fail("$message at $path: expected an object, got $a")
                a as JsonObject
                assertEquals("$message at $path: keys", e.keys, a.keys)
                for (name in e.keys) walk("$path.$name", e.getValue(name), a.getValue(name))
            }
            is JsonArray -> {
                if (a !is JsonArray) fail("$message at $path: expected a list, got $a")
                a as JsonArray
                assertEquals("$message at $path: length", e.size, a.size)
                for (i in e.indices) walk("$path[$i]", e[i], a[i])
            }
            is JsonNull -> assertEquals("$message at $path", JsonNull, a)
            is JsonPrimitive -> {
                if (a !is JsonPrimitive || a is JsonNull) fail("$message at $path: expected $e, got $a")
                a as JsonPrimitive
                val x = e.doubleOrNull
                val y = a.doubleOrNull
                if (e.isString || x == null || y == null) {
                    assertEquals("$message at $path", e.content, a.content)
                } else if (abs(x - y) > absolute) {
                    fail("$message at $path: expected $x, got $y (allowed $absolute)")
                }
            }
        }
    }
    walk("$", expected, actual)
}
