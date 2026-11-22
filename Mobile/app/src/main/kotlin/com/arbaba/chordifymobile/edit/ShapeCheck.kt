package com.arbaba.chordifymobile.edit

import com.arbaba.chordifymobile.engine.vocab.degreeToSemitones
import com.arbaba.chordifymobile.engine.vocab.displayName
import com.arbaba.chordifymobile.engine.vocab.parse
import com.arbaba.chordifymobile.engine.vocab.pcToName

/** Pitch classes of the open strings E A D G B e. */
internal val OPEN_STRING_PCS: List<Int> = listOf(4, 9, 2, 7, 11, 4)

/** The pitch classes a shape sounds (muted strings are skipped). Frets are counted from the capo, as in the shape label. */
internal fun shapePitchClasses(shape: List<Int>): Set<Int> =
    shape.withIndex().filter { it.value >= 0 }.map { Math.floorMod(OPEN_STRING_PCS[it.index] + it.value, 12) }.toSet()

private fun ordinal(degree: String): String {
    val number = degree.trimStart('b', '#').trimStart('b')
    return when (number) {
        "2" -> "2nd"
        "3" -> "3rd"
        else -> number + "th"
    }
}

/** Null when [shape] sounds every tone of the chord [label] (a shape label such as "G:maj");
 *  otherwise the first missing tone, in the order root, 3rd, 5th, 7th, other:
 *  "This shape has no B, the 3rd of G". Never blocks saving. */
fun missingNote(shape: List<Int>, label: String): String? {
    val chord = try { parse(label) } catch (_: IllegalArgumentException) { return null }
    val root = chord.root ?: return null
    val flats = label.substringBefore(':').substringBefore('/').drop(1).startsWith("b")
    val have = shapePitchClasses(shape)
    fun rank(degree: String): Int = when (degree.trimStart('b', '#')) {
        "1" -> 0
        "3" -> 1
        "5" -> 2
        "7" -> 3
        else -> 4
    }
    val ordered = chord.degrees.sortedWith(compareBy({ rank(it) }, { degreeToSemitones(it) }))
    val missing = ordered.firstOrNull { Math.floorMod(root + degreeToSemitones(it), 12) !in have } ?: return null
    val pc = Math.floorMod(root + degreeToSemitones(missing), 12)
    val role = if (rank(missing) == 0) "the root" else "the ${ordinal(missing)}"
    val chordName = displayName(label, flats).substringBefore('/')
    return "This shape has no ${pcToName(pc, flats)}, $role of $chordName"
}
