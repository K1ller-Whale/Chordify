package com.arbaba.chordifymobile.ui.analysis

import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ServerJson
import com.arbaba.chordifymobile.music.WebGoldens
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartsTest {
    /** loop.wav's analysis: C, G, Am, F in C major. */
    private val loop: AnalysisResult = ServerJson.decodeFromJsonElement(
        AnalysisResult.serializer(), WebGoldens.json.getValue("results").jsonArray[0].jsonObject.getValue("result"))

    @Test
    fun aChangeIsSurprisingWhenTheModelGaveItLessThanFifteenPercent() {
        val chords = loop.chords.mapIndexed { i, c -> c.copy(surprise = listOf(null, 2.0, 3.0, 2.8)[i % 4]) }
        assertEquals(chords.filterIndexed { i, _ -> i % 4 >= 2 }.map { it.index }.toSet(), surprisingChords(chords))
        assertEquals(emptySet<Int>(), surprisingChords(listOf(chords[2].copy(label = "N"))))
    }

    @Test
    fun barsChordsAndSeekingAreFoundAsOnTheWeb() {
        assertEquals(-1, barIndexAt(listOf(1.0, 3.0), 0.5))
        assertEquals(0, barIndexAt(listOf(1.0, 3.0), 1.0))
        assertEquals(1, barIndexAt(listOf(1.0, 3.0), 3.5))
        assertEquals(-1, barIndexAt(emptyList(), 3.5))

        val starts = listOf(0.0, 2.0, 4.0, 6.0)
        assertEquals(4.01, chordStep(starts, 2.5, 1), 1e-12)
        assertEquals(0.01, chordStep(starts, 2.5, -1), 1e-12)
        assertEquals(6.01, chordStep(starts, 6.5, 1), 1e-12)
        assertEquals(0.01, chordStep(starts, 0.0, -1), 1e-12)

        assertEquals(10.0, detailSeek(10.0, 330f, 1000f, 100f), 1e-9)
        assertEquals(11.0, detailSeek(10.0, 430f, 1000f, 100f), 1e-9)
        assertEquals(6.0, overviewSeek(750f, 1000f, 8.0), 1e-9)
    }

    @Test
    fun theChordsSitOnTheCircleOfFifthsAsOnTheWeb() {
        val nodes = circleNodes(loop)
        assertEquals(listOf("0:M", "7:M", "9:m", "5:M"), nodes.map { it.key })
        assertEquals(listOf("C", "G", "Am", "F"), nodes.map { it.text })
        assertEquals(listOf("C", "G", "Am", "F"), nodes.map { it.label })
        assertEquals(listOf(0, 1, 0, 11), nodes.map { it.index })
        assertEquals(listOf(false, false, true, false), nodes.map { it.minor })
        val share = loop.summary.timeShare.associate { it.display to it.share }
        assertEquals(nodes.map { share.getValue(it.chord.display) }, nodes.map { it.share })

        val transitions = circleTransitions(loop)
        assertEquals(1, transitions["0:M" to "7:M"])
        assertEquals(1, transitions["7:M" to "9:m"])
        assertEquals(1, transitions["9:m" to "5:M"])
        assertNull(nodeKey(loop.chords[0].copy(label = "N")))
        assertEquals("1:m", nodeKey(loop.chords[0].copy(root = "Db", quality = "min7")))
    }
}
