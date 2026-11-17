package com.arbaba.chordifymobile.engine.vocab

import com.arbaba.chordifymobile.engine.util.partition3
import java.util.concurrent.ConcurrentHashMap

// Port of chordify_core/vocab.py: Harte chord labels and pitch-class maths. Everything works
// on intervals and pitch classes, never on label strings, so enharmonic spellings (Bb vs A#)
// cannot split a class. Harte syntax: root[:shorthand][(degrees)][/bass], e.g. A:min7,
// C:maj/3, G:sus4(b7,9), D:(1,b3,5), N (no chord), X (unknown).

const val NO_CHORD = "N"
const val UNKNOWN = "X"

val SHARP_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
val FLAT_NAMES = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
private val NATURALS = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

// Scale degree -> semitones above the root (compound degrees keep their octave).
private val DEGREE_SEMITONES = mapOf(1 to 0, 2 to 2, 3 to 4, 4 to 5, 5 to 7, 6 to 9, 7 to 11, 8 to 12, 9 to 14,
                                     10 to 16, 11 to 17, 12 to 19, 13 to 21)

// Harte shorthands as degree lists.
val SHORTHANDS: Map<String, List<String>> = mapOf(
    "maj" to listOf("1", "3", "5"),
    "min" to listOf("1", "b3", "5"),
    "dim" to listOf("1", "b3", "b5"),
    "aug" to listOf("1", "3", "#5"),
    "maj7" to listOf("1", "3", "5", "7"),
    "min7" to listOf("1", "b3", "5", "b7"),
    "7" to listOf("1", "3", "5", "b7"),
    "dim7" to listOf("1", "b3", "b5", "bb7"),
    "hdim7" to listOf("1", "b3", "b5", "b7"),
    "minmaj7" to listOf("1", "b3", "5", "7"),
    "maj6" to listOf("1", "3", "5", "6"),
    "min6" to listOf("1", "b3", "5", "6"),
    "9" to listOf("1", "3", "5", "b7", "9"),
    "maj9" to listOf("1", "3", "5", "7", "9"),
    "min9" to listOf("1", "b3", "5", "b7", "9"),
    "11" to listOf("1", "3", "5", "b7", "9", "11"),
    "maj11" to listOf("1", "3", "5", "7", "9", "11"),
    "min11" to listOf("1", "b3", "5", "b7", "9", "11"),
    "13" to listOf("1", "3", "5", "b7", "9", "11", "13"),
    "maj13" to listOf("1", "3", "5", "7", "9", "11", "13"),
    "min13" to listOf("1", "b3", "5", "b7", "9", "11", "13"),
    "sus2" to listOf("1", "2", "5"),
    "sus4" to listOf("1", "4", "5"),
    "5" to listOf("1", "5"),
    "1" to listOf("1"),
)

/** 'C' -> 0, 'Bb' -> 10, 'E#' -> 5, 'Cbb' -> 10. */
fun noteToPc(name: String): Int {
    if (name.isEmpty() || name[0] !in NATURALS) throw IllegalArgumentException("not a note name: '$name'")
    var pc = NATURALS.getValue(name[0])
    for (accidental in name.substring(1)) {
        pc += when (accidental) {
            '#' -> 1
            'b' -> -1
            else -> throw IllegalArgumentException("not a note name: '$name'")
        }
    }
    return Math.floorMod(pc, 12)
}

fun pcToName(pc: Int, flats: Boolean = false): String = (if (flats) FLAT_NAMES else SHARP_NAMES)[Math.floorMod(pc, 12)]

/** 'b3' -> 3, '#11' -> 18, 'bb7' -> 9. */
fun degreeToSemitones(degree: String): Int {
    val stripped = degree.trimStart('b', '#')
    val modifier = degree.substring(0, degree.length - stripped.length)
    val base = stripped.toIntOrNull()?.let { DEGREE_SEMITONES[it] }
        ?: throw IllegalArgumentException("not a scale degree: '$degree'")
    return base + modifier.count { it == '#' } - modifier.count { it == 'b' }
}

/** A parsed chord. [root] is null for N and X; [bass] is absolute and equals [root] in root position. */
data class Chord(val label: String, val root: Int?, val degrees: Set<String>, val bass: Int?) {
    val isChord: Boolean get() = root != null

    /** Sounding pitch classes; a slash bass is a chord tone too (Harte semantics). */
    val pitchClasses: Set<Int>
        get() {
            val r = root ?: return emptySet()
            return degrees.map { Math.floorMod(r + degreeToSemitones(it), 12) }.toSet() + bass!!
        }

    /** "maj", "min" or null (sus and power chords). */
    val third: String?
        get() = when {
            "3" in degrees -> "maj"
            "b3" in degrees -> "min"
            else -> null
        }

    /** "perfect", "dim", "aug" or null. */
    val fifth: String?
        get() = when {
            "5" in degrees -> "perfect"
            "b5" in degrees -> "dim"
            "#5" in degrees -> "aug"
            else -> null
        }

    /** "maj" (7), "min" (b7), "dim" (bb7) or null. */
    val seventh: String?
        get() = when {
            "7" in degrees -> "maj"
            "b7" in degrees -> "min"
            "bb7" in degrees -> "dim"
            else -> null
        }

    val hasSixth: Boolean get() = "6" in degrees && seventh == null
}

private val parsed = ConcurrentHashMap<String, Chord>()

/** Parse a Harte label. Throws IllegalArgumentException for malformed labels. */
fun parse(label: String): Chord = parsed[label] ?: parseUncached(label).also { parsed[label] = it }

private fun parseUncached(raw: String): Chord {
    val label = raw.trim()
    if (label == NO_CHORD || label == UNKNOWN) return Chord(label, null, emptySet(), null)
    val (body, _, bassPart) = label.partition3('/')
    val (rootPart, hasQuality, qualityText) = body.partition3(':')
    val root = noteToPc(rootPart)
    val quality = if (hasQuality) qualityText else "maj"
    val (shorthand, paren, extra) = quality.partition3('(')
    if (paren && !extra.endsWith(")")) throw IllegalArgumentException("unbalanced parentheses in '$label'")
    if (shorthand.isNotEmpty() && shorthand !in SHORTHANDS) {
        throw IllegalArgumentException("unknown shorthand '$shorthand' in '$label'")
    }
    if (shorthand.isEmpty() && !paren) throw IllegalArgumentException("missing quality in '$label'")
    val degrees = SHORTHANDS[shorthand].orEmpty().toMutableSet()
    if (paren) {
        for (item in extra.dropLast(1).split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            if (item.startsWith("*")) {
                degrees.remove(item.substring(1))
            } else {
                degreeToSemitones(item) // validate
                degrees.add(item)
            }
        }
    }
    if (shorthand.isEmpty()) degrees.add("1")
    val bass = if (bassPart.isNotEmpty()) Math.floorMod(root + degreeToSemitones(bassPart), 12) else root
    return Chord(label, root, degrees.toSet(), bass)
}

/** Triad quality: maj, min, dim, aug, sus2, sus4, 5 (power), 1 (single note) or null. */
fun triad(chord: Chord): String? {
    if (!chord.isChord) return null
    val third = chord.third
    val fifth = chord.fifth
    if (third == "maj") return if (fifth == "aug") "aug" else "maj"
    if (third == "min") return if (fifth == "dim") "dim" else "min"
    if ("4" in chord.degrees && fifth == "perfect") return "sus4"
    if ("2" in chord.degrees && fifth == "perfect") return "sus2"
    if (fifth == "perfect") return "5"
    return "1"
}
