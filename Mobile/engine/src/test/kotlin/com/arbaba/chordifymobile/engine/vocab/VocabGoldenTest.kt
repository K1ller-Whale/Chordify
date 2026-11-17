package com.arbaba.chordifymobile.engine.vocab

import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.bool
import com.arbaba.chordifymobile.engine.has
import com.arbaba.chordifymobile.engine.int
import com.arbaba.chordifymobile.engine.ints
import com.arbaba.chordifymobile.engine.obj
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import com.arbaba.chordifymobile.engine.strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VocabGoldenTest {
    private val golden = Goldens.load("vocab")

    @Test
    fun everyLabelParsesAsInPython() {
        for (row in golden.objects("chords")) {
            val label = row.str("label")!!
            val chord = parse(label)
            val at = "'$label'"
            assertEquals(at, row.str("parsed"), chord.label)
            assertEquals(at, row.int("root"), chord.root)
            assertEquals(at, row.int("bass"), chord.bass)
            assertEquals(at, row.getValue("degrees").strings(), chord.degrees.sorted())
            assertEquals(at, row.getValue("pitch_classes").ints(), chord.pitchClasses.sorted())
            assertEquals(at, row.str("third"), chord.third)
            assertEquals(at, row.str("fifth"), chord.fifth)
            assertEquals(at, row.str("seventh"), chord.seventh)
            assertEquals(at, row.bool("has_sixth"), chord.hasSixth)
            assertEquals(at, row.str("triad"), triad(chord))
            assertEquals(at, row.getValue("chord_tones").ints(), chordTones(label))
        }
    }

    @Test
    fun everyLabelIsNamedAndTransposedAsInPython() {
        for (row in golden.objects("chords")) {
            val label = row.str("label")!!
            val display = row.obj("display")
            assertEquals("'$label'", display.str("auto"), displayName(label))
            assertEquals("'$label' with flats", display.str("flats"), displayName(label, flats = true))
            assertEquals("'$label' with sharps", display.str("sharps"), displayName(label, flats = false))
            val transposed = row.obj("transpose")
            assertEquals("'$label' -3", transposed.str("-3"), transpose(label, -3))
            assertEquals("'$label' -3 flats", transposed.str("-3b"), transpose(label, -3, flats = true))
            assertEquals("'$label' +5", transposed.str("5"), transpose(label, 5))
            assertEquals("'$label' +5 flats", transposed.str("5b"), transpose(label, 5, flats = true))
        }
    }

    @Test
    fun everyLabelMapsOntoEachVocabularyAsInPython() {
        for (row in golden.objects("chords")) {
            val label = row.str("label")!!
            for ((name, vocabulary) in VOCABULARIES) {
                val expected = row.obj(name)
                val at = "'$label' in $name"
                assertEquals(at, expected.int("encode"), vocabulary.encode(label))
                assertEquals(at, expected.str("reduce"), reduce(label, vocabulary))
                assertEquals(at, expected.str("reduce_keep_bass"), reduceKeepBass(label, vocabulary))
                assertEquals(at, expected.str("simplify"), simplify(label, vocabulary))
            }
        }
    }

    @Test
    fun vocabulariesMatchPython() {
        val expected = golden.objects("vocabularies")
        assertEquals(expected.map { it.str("name") }, VOCABULARIES.keys.toList())
        assertEquals(TIERS, VOCABULARIES.keys.toList())
        for (v in expected) {
            val vocabulary = VOCABULARIES.getValue(v.str("name")!!)
            val indices = 0 until vocabulary.size
            assertEquals(v.getValue("qualities").strings(), vocabulary.qualities)
            assertEquals(v.int("size"), vocabulary.size)
            assertEquals(v.int("no_chord"), vocabulary.noChord)
            assertEquals(v.getValue("labels").strings(), vocabulary.labels)
            for (shift in listOf(-5, 7)) {
                assertEquals(v.obj("transpose_index").getValue("$shift").ints(), indices.map { vocabulary.transposeIndex(it, shift) })
            }
            assertEquals(v.getValue("root_of").ints(), indices.map { vocabulary.rootOf(it) })
            assertEquals(v.getValue("quality_of").strings(), indices.map { vocabulary.qualityOf(it) })
            assertEquals(indices.toList(), vocabulary.labels.map { vocabulary.encode(it) })
        }
    }

    @Test
    fun withBassNamesInversionsAsInPython() {
        for (row in golden.objects("with_bass")) {
            val label = row.str("label")!!
            val bass = row.int("bass")!!
            assertEquals("$label over $bass", row.str("result"), withBass(label, bass))
        }
    }

    @Test
    fun malformedLabelsAreRefused() {
        for (label in golden.getValue("invalid").strings()) {
            assertThrows("'$label'", IllegalArgumentException::class.java) { parse(label!!) }
        }
    }

    @Test
    fun leadSheetNamesParseAsInPython() {
        for (row in golden.objects("parse_display")) {
            val name = row.str("name")!!
            if (row.has("error")) {
                assertThrows("'$name'", IllegalArgumentException::class.java) { parseDisplay(name) }
            } else {
                assertEquals("'$name'", row.str("value"), parseDisplay(name))
            }
        }
    }
}
