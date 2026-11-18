package com.arbaba.chordifymobile.music

import com.arbaba.chordifymobile.engine.model.ChordSegment
import kotlin.math.floor

// Port of chordify-frontend/src/lib/music.ts: chord names in both notations, harmonic
// functions, guitar shapes and the time helpers of the analysis screen. web.json, written by
// tools/web_goldens.mjs from the web app's own code, checks the port.

private val NATURALS = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

/** The web's noteToPc: lenient, as names here come from the engine. */
fun noteToPc(name: String): Int {
    var pc = NATURALS[name.firstOrNull()] ?: 0
    for (accidental in name.drop(1)) pc += when (accidental) { '#' -> 1; 'b' -> -1; else -> 0 }
    return Math.floorMod(pc, 12)
}

fun isNoChord(label: String): Boolean = label == "N" || label == "X"

enum class Notation { LETTERS, ROMAN }

/** What a chord is called on screen: its name, or its Roman numeral. */
fun chordName(chord: ChordSegment, notation: Notation): String = when {
    isNoChord(chord.label) -> "N.C."
    notation == Notation.ROMAN -> chord.roman ?: chord.display
    else -> chord.display
}

/** A chord's harmonic function, for its colour (the web's --tonic, --subdominant, ... tokens). */
enum class FunctionHue { TONIC, SUBDOMINANT, DOMINANT, BORROWED, NONE }

fun functionHue(function: String?): FunctionHue = when (function) {
    "tonic" -> FunctionHue.TONIC
    "subdominant" -> FunctionHue.SUBDOMINANT
    "dominant" -> FunctionHue.DOMINANT
    "borrowed" -> FunctionHue.BORROWED
    else -> FunctionHue.NONE
}

val FUNCTION_NAMES = mapOf("tonic" to "Tonic", "subdominant" to "Subdominant", "dominant" to "Dominant",
                           "borrowed" to "Outside the key")

fun isMinorQuality(quality: String?): Boolean =
    quality != null && (quality.startsWith("min") || quality == "dim" || quality == "hdim7")

/** Guitar fingering: frets per string E A D G B e; -1 muted, 0 open. */
typealias Shape = List<Int>

private val OPEN_SHAPES: Map<String, Shape> = linkedMapOf(
    "C:maj" to listOf(-1, 3, 2, 0, 1, 0), "D:maj" to listOf(-1, -1, 0, 2, 3, 2), "E:maj" to listOf(0, 2, 2, 1, 0, 0),
    "G:maj" to listOf(3, 2, 0, 0, 0, 3), "A:maj" to listOf(-1, 0, 2, 2, 2, 0), "A:min" to listOf(-1, 0, 2, 2, 1, 0),
    "D:min" to listOf(-1, -1, 0, 2, 3, 1), "E:min" to listOf(0, 2, 2, 0, 0, 0), "C:7" to listOf(-1, 3, 2, 3, 1, 0),
    "D:7" to listOf(-1, -1, 0, 2, 1, 2), "E:7" to listOf(0, 2, 0, 1, 0, 0), "G:7" to listOf(3, 2, 0, 0, 0, 1),
    "A:7" to listOf(-1, 0, 2, 0, 2, 0), "B:7" to listOf(-1, 2, 1, 2, 0, 2), "A:min7" to listOf(-1, 0, 2, 0, 1, 0),
    "E:min7" to listOf(0, 2, 0, 0, 0, 0), "D:min7" to listOf(-1, -1, 0, 2, 1, 1), "C:maj7" to listOf(-1, 3, 2, 0, 0, 0),
    "F:maj7" to listOf(-1, -1, 3, 2, 1, 0), "E:maj7" to listOf(0, 2, 1, 1, 0, 0), "D:maj7" to listOf(-1, -1, 0, 2, 2, 2),
    "G:maj7" to listOf(3, 2, 0, 0, 0, 2), "A:maj7" to listOf(-1, 0, 2, 1, 2, 0), "A:sus4" to listOf(-1, 0, 2, 2, 3, 0),
    "D:sus4" to listOf(-1, -1, 0, 2, 3, 3), "E:sus4" to listOf(0, 2, 2, 2, 0, 0), "A:sus2" to listOf(-1, 0, 2, 2, 0, 0),
    "D:sus2" to listOf(-1, -1, 0, 2, 3, 0),
)

// Movable shapes relative to the root fret (null = muted); E-shapes put the root on string 6,
// A-shapes on string 5. Every chord type of the large vocabulary has at least one.
private val X: Int? = null
private val E_SHAPES: Map<String, List<Int?>> = mapOf(
    "maj" to listOf(0, 2, 2, 1, 0, 0), "min" to listOf(0, 2, 2, 0, 0, 0), "7" to listOf(0, 2, 0, 1, 0, 0),
    "min7" to listOf(0, 2, 0, 0, 0, 0), "maj7" to listOf(0, X, 1, 1, 0, X), "sus4" to listOf(0, 2, 2, 2, 0, 0),
    "maj6" to listOf(0, X, -1, 1, 0, X), "min6" to listOf(0, X, -1, 0, 0, X), "dim" to listOf(0, 1, 2, 0, X, X),
    "aug" to listOf(0, X, 2, 1, 1, 0), "dim7" to listOf(0, X, -1, 0, -1, X), "hdim7" to listOf(0, X, 0, 0, -1, X),
    "minmaj7" to listOf(0, X, 1, 0, 0, X),
)
private val A_SHAPES: Map<String, List<Int?>> = mapOf(
    "maj" to listOf(X, 0, 2, 2, 2, 0), "min" to listOf(X, 0, 2, 2, 1, 0), "7" to listOf(X, 0, 2, 0, 2, 0),
    "min7" to listOf(X, 0, 2, 0, 1, 0), "maj7" to listOf(X, 0, 2, 1, 2, 0), "sus4" to listOf(X, 0, 2, 2, 3, 0),
    "sus2" to listOf(X, 0, 2, 2, 0, 0), "maj6" to listOf(X, 0, 2, 2, 2, 2), "min6" to listOf(X, 0, 2, 2, 1, 2),
    "dim" to listOf(X, 0, 1, 2, 1, X), "aug" to listOf(X, 0, 3, 2, 2, 1), "dim7" to listOf(X, 0, 1, 2, 1, 2),
    "hdim7" to listOf(X, 0, 1, 0, 1, X), "minmaj7" to listOf(X, 0, 2, 1, 1, 0),
)

private fun openShape(pc: Int, quality: String): Shape? =
    OPEN_SHAPES.entries.firstOrNull { (label, _) ->
        val (root, q) = label.split(":")
        noteToPc(root) == pc && q == quality
    }?.value

/** Whether a chord (root pitch class and quality) has an open-position shape. */
fun hasOpenShape(pc: Int, quality: String): Boolean = openShape(pc, quality) != null

/** A playable guitar shape for a chord, or null when there is none for its quality. */
fun guitarShape(root: String?, quality: String?): Shape? {
    if (root.isNullOrEmpty() || quality.isNullOrEmpty()) return null
    val pc = noteToPc(root)
    openShape(pc, quality)?.let { return it }
    val eFret = Math.floorMod(pc - 4, 12).let { if (it == 0) 12 else it }
    val aFret = Math.floorMod(pc - 9, 12).let { if (it == 0) 12 else it }
    val (fret, shape) = listOf(eFret to E_SHAPES[quality], aFret to A_SHAPES[quality])
        .filter { it.second != null }.minByOrNull { it.first } ?: return null
    return shape!!.map { if (it == null) -1 else it + fret }
}

fun guitarShape(chord: ChordSegment): Shape? = guitarShape(chord.root, chord.quality)

/** m:ss, as the web shows times. */
fun formatTime(seconds: Double): String {
    val s = maxOf(0.0, seconds)
    return "${floor(s / 60).toInt()}:${floor(s % 60).toInt().toString().padStart(2, '0')}"
}

/** Index of the chord sounding at time [t] (chords sorted by start). */
fun chordIndexAt(starts: List<Double>, t: Double): Int {
    var lo = 0
    var hi = starts.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) shr 1
        if (starts[mid] <= t) lo = mid else hi = mid - 1
    }
    return maxOf(0, lo)
}

val MAJOR_CIRCLE = listOf("C", "G", "D", "A", "E", "B", "F#", "Db", "Ab", "Eb", "Bb", "F")
val MINOR_CIRCLE = listOf("Am", "Em", "Bm", "F#m", "C#m", "G#m", "D#m", "Bbm", "Fm", "Cm", "Gm", "Dm")

/** Position on the circle of fifths (0 = C / Am) of a pitch class. */
fun circleIndex(pc: Int, minor: Boolean): Int = ((if (minor) (pc + 3) % 12 else pc) * 7) % 12
