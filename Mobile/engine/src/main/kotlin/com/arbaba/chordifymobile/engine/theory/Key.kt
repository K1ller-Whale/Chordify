package com.arbaba.chordifymobile.engine.theory

import com.arbaba.chordifymobile.engine.vocab.noteToPc
import com.arbaba.chordifymobile.engine.vocab.parse
import com.arbaba.chordifymobile.engine.vocab.pcToName
import com.arbaba.chordifymobile.engine.vocab.triad
import kotlin.math.exp
import kotlin.math.sqrt

// Keys from chordify_core/theory.py: spelling, parsing, and the key of a song from its chords.

val MAJOR_SCALE = listOf(0, 2, 4, 5, 7, 9, 11)
val NATURAL_MINOR = listOf(0, 2, 3, 5, 7, 8, 10)
// Minor keys also use the raised 7th (harmonic minor), which makes V and vii° diatonic.
val MINOR_SCALE_EXTENDED = NATURAL_MINOR + 11

// Keys conventionally written with flats.
private val FLAT_MAJOR = setOf(5, 10, 3, 8, 1) // F Bb Eb Ab Db (F# major preferred over Gb)
private val FLAT_MINOR = setOf(2, 7, 0, 5, 10, 3) // D G C F Bb Eb

// Krumhansl & Kessler (1982) key profiles.
private val KK_MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
private val KK_MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

data class Key(val tonic: Int, val mode: String = "major") {
    val flats: Boolean get() = tonic in (if (mode == "major") FLAT_MAJOR else FLAT_MINOR)
    val tonicName: String get() = pcToName(tonic, flats)
    val name: String get() = "$tonicName $mode"
    val scale: List<Int> get() = if (mode == "major") MAJOR_SCALE else MINOR_SCALE_EXTENDED

    fun contains(pitchClasses: Iterable<Int>): Boolean {
        val inKey = scale.map { Math.floorMod(tonic + it, 12) }.toSet()
        return pitchClasses.all { it in inKey }
    }

    fun spell(pc: Int): String = pcToName(pc, flats)
}

private val KEY_PATTERN = Regex("([A-G][#b]*)\\s*(?::?\\s*(maj|major|min|minor|m))?", RegexOption.IGNORE_CASE)

/** 'D', 'D:maj', 'D major', 'Bb:min', 'F#m', 'A minor' -> Key. */
fun parseKey(text: String): Key {
    val trimmed = text.trim()
    val match = KEY_PATTERN.matchEntire(trimmed) ?: throw IllegalArgumentException("not a key: '$trimmed'")
    val mode = (match.groups[2]?.value ?: "maj").lowercase()
    return Key(noteToPc(match.groupValues[1]), if (mode in setOf("min", "minor", "m")) "minor" else "major")
}

val ALL_KEYS: List<Key> = listOf("major", "minor").flatMap { mode -> (0 until 12).map { Key(it, mode) } }

/** Correlation of a 12-bin pitch-class distribution with every key's Krumhansl–Kessler profile, best first. */
fun keyScores(chroma: DoubleArray): List<Pair<Key, Double>> {
    val varies = std(chroma) > 0
    return ALL_KEYS.map { key ->
        val profile = roll(if (key.mode == "major") KK_MAJOR else KK_MINOR, key.tonic)
        key to (if (varies) correlation(chroma, profile) else 0.0)
    }.sortedByDescending { it.second }
}

/** Key from [(label, duration), ...]: duration-weighted chord tones correlated with key profiles,
 *  plus a bonus for keys whose tonic chord opens or closes the song. Returns (key, confidence in
 *  0..1: the softmax margin over the 24 candidates). */
fun estimateKeyFromChords(chords: List<Pair<String, Double>>): Pair<Key, Double> {
    val real = chords.map { (label, duration) -> parse(label) to duration }.filter { it.first.isChord }
    if (real.isEmpty()) return Key(0, "major") to 0.0
    val histogram = DoubleArray(12)
    for ((chord, duration) in real) {
        for (pc in chord.pitchClasses) histogram[pc] += duration
        histogram[chord.root!!] += 0.5 * duration
    }
    val ends = listOf(real.first().first, real.last().first)
    val scored = keyScores(histogram).map { (key, base) ->
        var score = base
        for (chord in ends) {
            if (chord.root == key.tonic && triad(chord) == (if (key.mode == "major") "maj" else "min")) score += 0.1
        }
        key to score
    }.sortedByDescending { it.second }
    val values = scored.map { it.second * 20.0 }
    val top = values.max()
    val weights = values.map { exp(it - top) }
    return scored[0].first to weights[0] / weights.sum()
}

/** numpy's `values.std()`: the population standard deviation. */
private fun std(values: DoubleArray): Double {
    val mean = values.sum() / values.size
    return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
}

/** numpy's `np.roll(profile, shift)`. */
private fun roll(profile: DoubleArray, shift: Int): DoubleArray =
    DoubleArray(profile.size) { profile[Math.floorMod(it - shift, profile.size)] }

/** numpy's `np.corrcoef(x, y)[0, 1]`, computed in the same order. */
private fun correlation(x: DoubleArray, y: DoubleArray): Double {
    val n = x.size
    val mx = x.sum() / n
    val my = y.sum() / n
    var sxy = 0.0
    var sxx = 0.0
    var syy = 0.0
    for (i in 0 until n) {
        val dx = x[i] - mx
        val dy = y[i] - my
        sxy += dx * dy
        sxx += dx * dx
        syy += dy * dy
    }
    val dof = (n - 1).toDouble()
    return ((sxy / dof) / sqrt(sxx / dof) / sqrt(syy / dof)).coerceIn(-1.0, 1.0)
}
