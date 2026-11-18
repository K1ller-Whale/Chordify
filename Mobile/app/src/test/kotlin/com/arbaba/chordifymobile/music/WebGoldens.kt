package com.arbaba.chordifymobile.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import kotlin.math.abs

/** web.json, written by tools/web_goldens.mjs from the web app's music.ts and capo.ts. */
object WebGoldens {
    val json: JsonObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("goldens/web.json") ?: error("goldens/web.json is missing")
        Json.parseToJsonElement(stream.bufferedReader(Charsets.UTF_8).use { it.readText() }).jsonObject
    }
}

/** The same JSON, numbers compared as numbers (JavaScript writes 2 where Kotlin writes 2.0). */
fun assertSameJson(message: String, expected: JsonElement, actual: JsonElement) {
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
                if (e.isString || x == null || y == null) assertEquals("$message at $path", e.content, a.content)
                else if (abs(x - y) > 1e-12) fail("$message at $path: expected $x, got $y")
            }
        }
    }
    walk("$", expected, actual)
}
