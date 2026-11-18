package com.arbaba.chordifymobile.music

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicGoldenTest {
    private val golden = WebGoldens.json

    private fun JsonObject.list(name: String) = getValue(name).jsonArray.map { it.jsonObject }
    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

    @Test
    fun noteNamesAreReadAsTheWebReadsThem() {
        for (note in golden.list("notes")) {
            assertEquals(note.text("name"), note.getValue("pc").jsonPrimitive.int, noteToPc(note.text("name")))
        }
    }

    @Test
    fun everyChordTypeInEveryKeyHasTheWebsGuitarShape() {
        val shapes = golden.list("shapes")
        assertEquals(12 * 14, shapes.size)
        for (row in shapes) {
            val name = "${row.text("root")}:${row.text("quality")}"
            val shape = row.getValue("shape")
            val expected = if (shape is JsonNull) null else shape.jsonArray.map { it.jsonPrimitive.int }
            assertEquals(name, expected, guitarShape(row.text("root"), row.text("quality")))
            assertEquals(name, row.getValue("open").jsonPrimitive.boolean, hasOpenShape(noteToPc(row.text("root")), row.text("quality")))
            assertEquals(name, row.getValue("minor").jsonPrimitive.boolean, isMinorQuality(row.text("quality")))
        }
    }

    @Test
    fun theCapoMovesTheKeyAsOnTheWeb() {
        for (row in golden.list("keys")) {
            val shapes = row.getValue("shapes").jsonObject
            assertEquals("$row", ShapeKey(shapes.text("tonic"), shapes.text("mode"), shapes.getValue("flats").jsonPrimitive.boolean),
                         shapeKey(row.text("tonic"), row.text("mode"), row.getValue("capo").jsonPrimitive.int))
        }
    }

    @Test
    fun namesAndLabelsAreTransposedAsOnTheWeb() {
        for (row in golden.list("transposed")) {
            val shift = row.getValue("shift").jsonPrimitive.int
            val flats = row.getValue("flats").jsonPrimitive.boolean
            val value = row.text("value")
            val actual = if (row.text("kind") == "display") transposeDisplay(value, shift, flats) else transposeLabel(value, shift, flats)
            assertEquals("$row", row.text("out"), actual)
        }
    }

    @Test
    fun theCircleTimesAndChordLookupMatchTheWeb() {
        for (row in golden.list("circle")) {
            assertEquals("$row", row.getValue("index").jsonPrimitive.int,
                         circleIndex(row.getValue("pc").jsonPrimitive.int, row.getValue("minor").jsonPrimitive.boolean))
        }
        for (row in golden.list("times")) assertEquals("$row", row.text("text"), formatTime(row.getValue("seconds").jsonPrimitive.double))
        val lookup = golden.getValue("chord_at").jsonObject
        val starts = lookup.getValue("starts").jsonArray.map { it.jsonPrimitive.double }
        for (row in lookup.list("cases")) {
            assertEquals("$row", row.getValue("index").jsonPrimitive.int, chordIndexAt(starts, row.getValue("t").jsonPrimitive.double))
        }
        assertEquals(golden.getValue("max_capo").jsonPrimitive.int, MAX_CAPO)
    }
}
