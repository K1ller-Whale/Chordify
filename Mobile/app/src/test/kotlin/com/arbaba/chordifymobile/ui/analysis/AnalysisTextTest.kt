package com.arbaba.chordifymobile.ui.analysis

import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.Alternative
import com.arbaba.chordifymobile.engine.model.ServerJson
import com.arbaba.chordifymobile.engine.lm.PriorEntry
import com.arbaba.chordifymobile.music.CapoSuggestion
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.music.WebGoldens
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalysisTextTest {
    /** loop.wav's analysis: C, G, Am, F, two seconds each, in C major. */
    private val loop: AnalysisResult = ServerJson.decodeFromJsonElement(
        AnalysisResult.serializer(), WebGoldens.json.getValue("results").jsonArray[0].jsonObject.getValue("result"))
    private val c = loop.chords[0]
    private val g = loop.chords[1]

    @Test
    fun theCountdownCountsTheBeatsLeftInTheChord() {
        val beats = listOf(0.0, 0.5, 1.0, 1.5, 2.0)
        val chord = c.copy(start = 0.0, end = 2.0)
        assertEquals("2 beats until the change (1.5 s)", countdown(chord, beats, 0.5))
        assertEquals("1 beat until the change (0.6 s)", countdown(chord, beats, 1.4))
        assertEquals("0 beats until the change (0.0 s)", countdown(chord, beats, 2.5))
        assertEquals("", countdown(chord.copy(label = "N"), beats, 0.5))
    }

    @Test
    fun theFunctionAndTheDoubtAreSpelledOut() {
        assertEquals("Tonic in C major", functionLine(c, loop))
        assertNull(functionLine(c.copy(function = null), loop))
        val doubtful = c.copy(confidence = 0.4, alternatives = listOf(Alternative("A:min", "Am", 0.3), Alternative("E:min", "Em", 0.2),
                                                                       Alternative("F:maj", "F", 0.1)))
        assertEquals("Unsure: Am or Em also possible", unsure(doubtful))
        assertNull(unsure(c.copy(confidence = 0.9)))
    }

    @Test
    fun theActualNextChordIsComparedWithThePredictions() {
        assertEquals(g, actualNext(loop.chords, c))
        assertEquals(c, actualNext(loop.chords, c.copy(label = "N", index = 99))) // as on the web: the first chord
        assertNull(actualNext(loop.chords, loop.chords.last()))
        val ranked = c.next.indexOfFirst { it.roman == (g.romanTriad ?: g.roman) }
        val expected = if (ranked >= 0) "Actual next: G ✓ predicted #${ranked + 1}" else "Actual next: G ✦ not in the top 3"
        assertEquals(expected, actualNextLine(c, g, Notation.LETTERS))
        assertNull(actualNextLine(c.copy(next = emptyList()), g, Notation.LETTERS))
        assertEquals("Without this song’s history: I 40% · V 31%",
                     theoryOnlyLine(c.copy(theoryOnly = listOf(PriorEntry("I", 0.4), PriorEntry("V", 0.305)))))
        assertEquals(31, percent(0.305))
        assertEquals(100, percent(0.9999))
    }

    @Test
    fun theCapoNoticesReadAsOnTheWeb() {
        assertEquals("Capo on fret 5: chords are shown as the shapes you play, in G major. The song sounds in C major.", capoNotice(loop, 5))
        assertNull(capoNotice(loop, 0))
        assertEquals("With the capo on fret 5, 100% of the song is open chords.", suggestionNotice(CapoSuggestion(5, 1.0), 0))
        assertNull(suggestionNotice(CapoSuggestion(5, 1.0), 5))
        assertNull(suggestionNotice(CapoSuggestion(0, 0.75), 0))
        assertEquals("Guitar chord diagram, frets x 3 2 0 1 0, counted from a capo on fret 2", diagramDescription(listOf(-1, 3, 2, 0, 1, 0), 2))
    }

    @Test
    fun theM7LinesAreWorded() {
        assertEquals("I · Tonic", theoryLine(c, Notation.LETTERS))
        assertEquals("C · Tonic", theoryLine(c, Notation.ROMAN))
        assertEquals("No chord", theoryLine(c.copy(label = "N"), Notation.LETTERS))
        assertEquals("Capo 2 · sounds as Bb", soundsAsLine(c, 2, "Bb"))
        assertNull(soundsAsLine(c, 0, "Bb"))
        assertEquals("Capo: none", capoLabel(0))
        assertEquals("Capo: fret 3", capoLabel(3))
        assertEquals("fret 5  ★ 100% open chords", capoMenuLabel(5, CapoSuggestion(5, 1.0)))
        assertEquals("none", capoMenuLabel(0, CapoSuggestion(5, 1.0)))
        assertEquals("fret 5", capoMenuLabel(5, CapoSuggestion(0, 1.0)))
        assertEquals("C major · 118 BPM · 4/4", songFacts(loop))
        assertEquals(songFacts(loop) + " · 0:08 · 4 chords", songDetails(loop))
        assertEquals("0.5×", speedLabel(0.5))
        assertEquals("0.75×", speedLabel(0.75))
        assertEquals("1×", speedLabel(1.0))
        assertEquals("1.25×", speedLabel(1.25))
    }
}
