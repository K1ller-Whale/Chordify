package com.arbaba.chordifymobile.engine.theory

import com.arbaba.chordifymobile.engine.util.partition3
import com.arbaba.chordifymobile.engine.vocab.Chord
import com.arbaba.chordifymobile.engine.vocab.parse
import com.arbaba.chordifymobile.engine.vocab.triad

// Chords in a key, from chordify_core/theory.py: Roman numerals, harmonic function, scales,
// borrowed chords, cadences. It explains progressions in musicians' terms; it never predicts.

private val DEGREE_NAMES = mapOf(
    "major" to mapOf(0 to "I", 1 to "bII", 2 to "II", 3 to "bIII", 4 to "III", 5 to "IV", 6 to "#IV", 7 to "V",
                     8 to "bVI", 9 to "VI", 10 to "bVII", 11 to "VII"),
    "minor" to mapOf(0 to "I", 1 to "bII", 2 to "II", 3 to "III", 4 to "#III", 5 to "IV", 6 to "#IV", 7 to "V",
                     8 to "VI", 9 to "#VI", 10 to "VII", 11 to "#VII"),
)
private val FUNCTIONS = mapOf(
    "major" to mapOf(0 to "tonic", 4 to "tonic", 9 to "tonic", 2 to "subdominant", 5 to "subdominant",
                     7 to "dominant", 11 to "dominant"),
    "minor" to mapOf(0 to "tonic", 3 to "tonic", 8 to "tonic", 2 to "subdominant", 5 to "subdominant",
                     7 to "dominant", 10 to "dominant", 11 to "dominant"),
)
private val MODES = mapOf(
    "major" to mapOf(0 to "Ionian", 2 to "Dorian", 4 to "Phrygian", 5 to "Lydian", 7 to "Mixolydian",
                     9 to "Aeolian", 11 to "Locrian"),
    "minor" to mapOf(0 to "Aeolian", 2 to "Locrian", 3 to "Ionian", 5 to "Dorian", 7 to "Phrygian",
                     8 to "Lydian", 10 to "Mixolydian"),
)
private val SECONDARY_TARGETS = mapOf(
    "major" to mapOf(2 to "ii", 4 to "iii", 5 to "IV", 7 to "V", 9 to "vi"),
    "minor" to mapOf(3 to "III", 5 to "iv", 7 to "V", 8 to "VI", 10 to "VII"),
)
private val NUMERAL_LETTERS = Regex("[IV]+")

/** Roman numeral relative to the key: 'V', 'vi', 'bVII', 'vii°', 'V/V'. Minor-family chords are
 *  lower case; diminished get °, half-diminished ø, augmented +. [withQuality] adds the chord
 *  type: 'V7', 'IVmaj7', 'viiø7'. */
fun roman(label: String, key: Key, withQuality: Boolean = false): String? {
    val chord = parse(label)
    val root = chord.root ?: return null
    val secondary = secondaryDominant(label, key)
    if (secondary != null) {
        val suffix = if (withQuality) qualitySuffix(chord) else ""
        val (head, _, target) = secondary.partition3('/')
        return if (suffix.isNotEmpty()) "$head$suffix/$target" else secondary
    }
    var numeral = DEGREE_NAMES.getValue(key.mode).getValue(Math.floorMod(root - key.tonic, 12))
    val kind = triad(chord)
    if (kind == "min" || kind == "dim") numeral = numeral.replace(NUMERAL_LETTERS) { it.value.lowercase() }
    if (kind == "dim") {
        numeral += if (chord.seventh == "min") "ø" else "°"
    } else if (kind == "aug") {
        numeral += "+"
    }
    if (withQuality) numeral += qualitySuffix(chord)
    return numeral
}

/** What follows the numeral: 7, maj7, 6, sus4, ø7 / °7 (the ø/° is already on the numeral),
 *  (maj7) for a minor-major seventh. */
private fun qualitySuffix(chord: Chord): String {
    val kind = triad(chord)
    val seventh = chord.seventh
    return when {
        kind == "sus2" || kind == "sus4" -> (if (seventh == "min") "7" else "") + kind
        kind == "min" && seventh == "maj" -> "(maj7)"
        seventh == "min" || seventh == "dim" -> "7"
        seventh == "maj" -> "maj7"
        (kind == "maj" || kind == "min") && chord.hasSixth -> "6"
        else -> ""
    }
}

fun isDiatonic(label: String, key: Key): Boolean {
    val chord = parse(label)
    return chord.isChord && key.contains(chord.pitchClasses)
}

/** 'V/x' for a non-diatonic major or dominant-7th chord a fifth above a diatonic triad root. */
fun secondaryDominant(label: String, key: Key): String? {
    val chord = parse(label)
    if (!chord.isChord || isDiatonic(label, key) || triad(chord) != "maj" || chord.seventh == "maj") return null
    val target = Math.floorMod(chord.root!! - 7 - key.tonic, 12)
    if (target == 0 || target !in key.scale) return null
    val name = SECONDARY_TARGETS.getValue(key.mode)[target] ?: return null
    return "V/$name"
}

/** 'tonic' | 'subdominant' | 'dominant' | 'borrowed' (outside the key) | null for N. Python: `theory.function`. */
fun harmonicFunction(label: String, key: Key): String? {
    val chord = parse(label)
    val root = chord.root ?: return null
    if (!isDiatonic(label, key)) return "borrowed"
    return FUNCTIONS.getValue(key.mode)[Math.floorMod(root - key.tonic, 12)] ?: "borrowed"
}

/** A scale to play over the chord: the key's mode on the chord root when diatonic. */
fun scaleHint(label: String, key: Key): String? {
    val chord = parse(label)
    val root = chord.root ?: return null
    val name = key.spell(root)
    val offset = Math.floorMod(root - key.tonic, 12)
    if (isDiatonic(label, key)) {
        if (key.mode == "minor" && offset == 7 && triad(chord) == "maj") return "$name Phrygian dominant"
        if (key.mode == "minor" && offset == 11) return "$name diminished"
        MODES.getValue(key.mode)[offset]?.let { return "$name $it" }
    }
    val kind = triad(chord)
    return when (kind) {
        "maj" -> if (chord.seventh == "min") "$name Mixolydian" else "$name Lydian"
        "min" -> "$name Dorian"
        "sus2", "sus4", "5" -> "$name Mixolydian"
        "dim" -> "$name diminished"
        "aug" -> "$name whole tone"
        else -> null
    }
}

/** Parallel-mode source of a non-diatonic chord, e.g. bVII in D major -> 'D minor'. */
fun borrowedFrom(label: String, key: Key): String? {
    if (isDiatonic(label, key) || !parse(label).isChord) return null
    val parallel = Key(key.tonic, if (key.mode == "major") "minor" else "major")
    return if (isDiatonic(label, parallel)) parallel.name else null
}

/** What the analysis shows for a chord. [roman] names the chord type (V7, IVmaj7, viiø7);
 *  [romanTriad] is the bare numeral that progression patterns are matched on. */
data class Description(
    val roman: String?,
    val romanTriad: String?,
    val function: String?,
    val scaleHint: String?,
    val borrowedFrom: String?,
    val secondary: String?,
)

fun describe(label: String, key: Key): Description = Description(
    roman(label, key, withQuality = true), roman(label, key), harmonicFunction(label, key), scaleHint(label, key),
    borrowedFrom(label, key), secondaryDominant(label, key),
)

/** A numeral without its °, ø or + mark. */
internal fun baseNumeral(numeral: String?): String? = numeral?.trimEnd('°', 'ø', '+')

private val TRANSITIONS = mapOf(
    ("V" to "I") to "authentic cadence V→I", ("V" to "i") to "authentic cadence V→i",
    ("V" to "vi") to "deceptive cadence V→vi", ("V" to "VI") to "deceptive cadence V→VI",
    ("IV" to "I") to "plagal motion IV→I", ("iv" to "i") to "plagal motion iv→i", ("iv" to "I") to "minor plagal iv→I",
    ("ii" to "V") to "pre-dominant → dominant (ii→V)", ("IV" to "V") to "subdominant → dominant",
    ("vi" to "IV") to "vi→IV, the Axis loop", ("I" to "vi") to "tonic → relative minor",
    ("I" to "IV") to "tonic → subdominant", ("I" to "V") to "tonic → dominant", ("V" to "IV") to "rock “backdoor” V→IV",
    ("bVII" to "I") to "backdoor resolution bVII→I", ("bVI" to "bVII") to "bVI→bVII→I rise",
    ("vi" to "ii") to "descending fifths vi→ii", ("iii" to "vi") to "descending fifths iii→vi",
    ("IV" to "vi") to "subdominant → relative minor", ("i" to "VII") to "minor-key descent i→VII",
    ("VII" to "VI") to "descending bass VII→VI", ("VI" to "V") to "Andalusian step VI→V",
)

/** Short theory explanation of a chord change, or null if nothing notable. */
fun transitionReason(prevLabel: String, nextLabel: String, key: Key): String? {
    val a = baseNumeral(roman(prevLabel, key))
    val b = baseNumeral(roman(nextLabel, key))
    if (a == null || b == null || a == b) return null
    if (b.startsWith("V/")) return "secondary dominant $b leading to ${b.substring(2)}"
    if (a.startsWith("V/")) return "$a resolves"
    TRANSITIONS[a to b]?.let { return it }
    borrowedFrom(nextLabel, key)?.let { return "$b borrowed from $it" }
    val fa = harmonicFunction(prevLabel, key)
    val fb = harmonicFunction(nextLabel, key)
    if (fa != null && fb != null && fa != fb && fa != "borrowed" && fb != "borrowed") return "$fa → $fb"
    return null
}

fun cadence(prevLabel: String, nextLabel: String, key: Key): String? {
    val a = baseNumeral(roman(prevLabel, key)) ?: return null
    val b = baseNumeral(roman(nextLabel, key)) ?: return null
    return when {
        a == "V" && b.uppercase() == "I" -> "authentic"
        a.uppercase() == "IV" && b.uppercase() == "I" -> "plagal"
        a == "V" && b.uppercase() == "VI" -> "deceptive"
        b == "V" && a != "V" -> "half"
        else -> null
    }
}
