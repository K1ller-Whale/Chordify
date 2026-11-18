package com.arbaba.chordifymobile.ui.analysis

import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ChordSegment
import com.arbaba.chordifymobile.music.FUNCTION_NAMES
import com.arbaba.chordifymobile.music.CapoSuggestion
import com.arbaba.chordifymobile.music.Notation
import com.arbaba.chordifymobile.music.Shape
import com.arbaba.chordifymobile.music.chordName
import com.arbaba.chordifymobile.music.isNoChord
import com.arbaba.chordifymobile.music.shapeKey
import java.util.Locale
import kotlin.math.roundToLong

// What the analysis screen says, worded as the web app words it (NowAndNext.tsx and
// AnalysisPage.tsx), apart from its Android layout.

/** "3 beats until the change (1.4 s)", or nothing over "no chord". */
fun countdown(chord: ChordSegment, beats: List<Double>, time: Double): String {
    if (isNoChord(chord.label)) return ""
    val left = maxOf(0.0, chord.end - time)
    val beatsLeft = beats.count { it > time && it < chord.end - 0.05 }
    return "$beatsLeft beat${if (beatsLeft == 1) "" else "s"} until the change (${String.format(Locale.ROOT, "%.1f", left)} s)"
}

/** How far into the chord the playhead is, 0..1. */
fun elapsed(chord: ChordSegment, time: Double): Float =
    (1 - maxOf(0.0, chord.end - time) / maxOf(1e-6, chord.end - chord.start)).toFloat().coerceIn(0f, 1f)

/** "Tonic in C major", or null when the chord has no function. */
fun functionLine(chord: ChordSegment, result: AnalysisResult): String? {
    val name = FUNCTION_NAMES[chord.function] ?: return null
    val key = result.key.global
    return "$name in ${key.tonic} ${key.mode}"
}

/** "Unsure: Am or Em also possible", for a chord the model is less than half sure of. */
fun unsure(chord: ChordSegment): String? =
    if (chord.confidence < 0.5 && !isNoChord(chord.label) && chord.alternatives.isNotEmpty()) {
        "Unsure: ${chord.alternatives.take(2).joinToString(" or ") { it.display }} also possible"
    } else null

/** The chord after [chord] that was really played (the first chord when [chord] is "no chord"). */
fun actualNext(chords: List<ChordSegment>, chord: ChordSegment): ChordSegment? {
    val real = chords.filter { !isNoChord(it.label) }
    return real.getOrNull(real.indexOfFirst { it.index == chord.index } + 1)
}

/** "Actual next: G ✓ predicted #1" or "... ✦ not in the top 3"; predictions are bare numerals,
 *  so they are matched against the chord's bare numeral (V, not V7). */
fun actualNextLine(chord: ChordSegment, next: ChordSegment?, notation: Notation): String? {
    if (next == null || chord.next.isEmpty()) return null
    val rank = chord.next.indexOfFirst { it.roman == (next.romanTriad ?: next.roman) }
    return "Actual next: ${chordName(next, notation)} " + if (rank >= 0) "✓ predicted #${rank + 1}" else "✦ not in the top 3"
}

/** "Without this song’s history: I 40% · V 30%" */
fun theoryOnlyLine(chord: ChordSegment): String? =
    if (chord.theoryOnly.isEmpty()) null
    else "Without this song’s history: " + chord.theoryOnly.joinToString(" · ") { "${it.roman} ${percent(it.p)}%" }

/** JavaScript's Math.round(p * 100). */
fun percent(p: Double): Long = Math.floor(p * 100 + 0.5).roundToLong()

/** The capo notices above Now playing (AnalysisPage.tsx). */
fun capoNotice(analysed: AnalysisResult, capo: Int): String? {
    if (capo == 0) return null
    val key = analysed.key.global
    val mode = if (key.mode == "unknown") "" else " ${key.mode}"
    val shapes = shapeKey(key.tonic, key.mode, capo)
    return "Capo on fret $capo: chords are shown as the shapes you play, in ${shapes.tonic}$mode. The song sounds in ${key.tonic}$mode."
}

fun suggestionNotice(suggestion: CapoSuggestion, capo: Int): String? =
    if (suggestion.capo > 0 && suggestion.capo != capo) {
        "With the capo on fret ${suggestion.capo}, ${percent(suggestion.open)}% of the song is open chords."
    } else null

/** What a screen reader says for a chord box, as the web's aria-label. */
fun diagramDescription(shape: Shape?, capo: Int): String =
    if (shape == null) "No guitar diagram"
    else "Guitar chord diagram, frets ${shape.joinToString(" ") { if (it < 0) "x" else "$it" }}" +
        if (capo > 0) ", counted from a capo on fret $capo" else ""

const val FILE_MISSING ="The audio file is not where it was. Pick it again to play along; until then the chords run silently."
const val FILE_MISMATCH = "This file is not the one that was analysed, so chords may not line up."
