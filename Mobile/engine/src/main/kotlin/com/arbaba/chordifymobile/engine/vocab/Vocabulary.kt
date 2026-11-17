package com.arbaba.chordifymobile.engine.vocab

// Vocabulary tiers and label arithmetic from chordify_core/vocab.py.

val LARGE_QUALITIES = listOf("maj", "min", "dim", "aug", "maj6", "min6", "7", "maj7", "min7",
                             "dim7", "hdim7", "minmaj7", "sus2", "sus4")

private fun reduceMajmin(chord: Chord): String? = triad(chord).takeIf { it == "maj" || it == "min" }

private fun reduceSevenths(chord: Chord): String? = when (triad(chord)) {
    "maj" -> when (chord.seventh) {
        null -> "maj"
        "min" -> "7"
        "maj" -> "maj7"
        else -> null
    }
    "min" -> when (chord.seventh) {
        null -> "min"
        "min" -> "min7"
        else -> null
    }
    else -> null
}

private fun reduceLarge(chord: Chord): String? = when (triad(chord) to chord.seventh) {
    "maj" to null -> if (chord.hasSixth) "maj6" else "maj"
    "min" to null -> if (chord.hasSixth) "min6" else "min"
    "maj" to "min" -> "7"
    "maj" to "maj" -> "maj7"
    "min" to "min" -> "min7"
    "min" to "maj" -> "minmaj7"
    "dim" to null -> "dim"
    "dim" to "dim" -> "dim7"
    "dim" to "min" -> "hdim7"
    "aug" to null, "aug" to "min", "aug" to "maj" -> "aug"
    "sus2" to null, "sus2" to "min" -> "sus2"
    "sus4" to null, "sus4" to "min" -> "sus4"
    else -> null
}

/** A fixed list of chord classes: class i < 12·Q is root i % 12 with quality qualities[i / 12]; N is the last. */
class Vocabulary(val name: String, val qualities: List<String>) {
    val size: Int = 12 * qualities.size + 1
    val noChord: Int = size - 1
    val labels: List<String> = List(12 * qualities.size) { "${SHARP_NAMES[it % 12]}:${qualities[it / 12]}" } + NO_CHORD

    fun reduceQuality(chord: Chord): String? = when (name) {
        "majmin" -> reduceMajmin(chord)
        "sevenths" -> reduceSevenths(chord)
        "large" -> reduceLarge(chord)
        else -> throw IllegalStateException("unknown vocabulary '$name'")
    }

    /** Class index, or null when the chord is outside the vocabulary (X). */
    fun encode(label: String): Int? {
        val chord = parse(label)
        if (chord.label == NO_CHORD) return noChord
        val root = chord.root ?: return null
        val quality = reduceQuality(chord) ?: return null
        return qualities.indexOf(quality) * 12 + root
    }

    fun decode(index: Int): String = labels[index]

    fun transposeIndex(index: Int, semitones: Int): Int =
        if (index == noChord) index else (index / 12) * 12 + Math.floorMod(index % 12 + semitones, 12)

    fun rootOf(index: Int): Int? = if (index == noChord) null else index % 12

    fun qualityOf(index: Int): String? = if (index == noChord) null else qualities[index / 12]
}

val MAJMIN = Vocabulary("majmin", listOf("maj", "min"))
val SEVENTHS = Vocabulary("sevenths", listOf("maj", "min", "7", "maj7", "min7"))
val LARGE = Vocabulary("large", LARGE_QUALITIES)
val VOCABULARIES: Map<String, Vocabulary> = listOf(MAJMIN, SEVENTHS, LARGE).associateBy { it.name }

/** Each tier's chord types include the previous tier's. */
val TIERS = listOf("majmin", "sevenths", "large")

/** Map any Harte label onto a vocabulary tier, returning X when it has no class. */
fun reduce(label: String, vocabulary: Vocabulary = MAJMIN): String =
    vocabulary.encode(label)?.let(vocabulary::decode) ?: UNKNOWN

/** [label] played over [bassPc] when that note is one of its chord tones other than the root
 *  ('C:maj' + E -> 'C:maj/3', 'G:7' + F -> 'G:7/b7'); otherwise [label] unchanged. */
fun withBass(label: String, bassPc: Int): String {
    val chord = parse(label)
    val root = chord.root ?: return label
    if (Math.floorMod(bassPc, 12) == root) return label
    val interval = Math.floorMod(bassPc - root, 12)
    val degree = chord.degrees.sorted().firstOrNull { Math.floorMod(degreeToSemitones(it), 12) == interval }
        ?: return label
    return "${label.substringBefore('/')}/$degree"
}

/** Name [label] with the chord types of a coarser vocabulary, for display: like [reduceKeepBass],
 *  but a chord the tier cannot represent falls back to its major/minor family by its third
 *  ('B:hdim7' -> 'B:min' in majmin, 'C:sus4' -> 'C:maj') instead of X. */
fun simplify(label: String, vocabulary: Vocabulary): String {
    val simplified = reduceKeepBass(label, vocabulary)
    val chord = parse(label)
    if (simplified != UNKNOWN || !chord.isChord) return simplified
    val family = "${SHARP_NAMES[chord.root!!]}:${if (chord.third == "min") "min" else "maj"}"
    return withBass(family, chord.bass!!)
}

/** Like [reduce], but an inversion survives while its bass is still a chord tone of the reduced
 *  chord: 'C:maj7/3' -> 'C:maj/3' in majmin, while 'C:maj7/7' -> 'C:maj'. */
fun reduceKeepBass(label: String, vocabulary: Vocabulary): String {
    val reduced = reduce(label, vocabulary)
    val chord = parse(label)
    if (reduced == UNKNOWN || reduced == NO_CHORD || !chord.isChord || chord.bass == chord.root) return reduced
    return withBass(reduced, chord.bass!!)
}

/** The root as written at the start of a label: 'Bb:min7' -> 'Bb'. */
internal fun rootText(label: String): String {
    var end = 1
    while (end < label.length && label[end] in "#b") end++
    return label.take(end)
}

/** Transpose a Harte label, keeping its quality, degrees and bass interval. */
fun transpose(label: String, semitones: Int, flats: Boolean = false): String {
    val chord = parse(label)
    val root = chord.root ?: return chord.label
    val stripped = label.trim()
    return pcToName(root + semitones, flats) + stripped.substring(rootText(stripped).length)
}

/** 12 zeros and ones: the chord's pitch classes (absolute). */
fun chordTones(label: String): List<Int> {
    val pitchClasses = parse(label).pitchClasses
    return List(12) { if (it in pitchClasses) 1 else 0 }
}
