package com.arbaba.chordifymobile.engine.vocab

import com.arbaba.chordifymobile.engine.util.partition3

// Lead-sheet names from chordify_core/vocab.py: Harte label <-> 'Am7', 'C/E', 'A7sus4'.

private val DISPLAY_SUFFIX = mapOf(
    "maj" to "", "min" to "m", "dim" to "dim", "aug" to "aug", "maj7" to "maj7", "min7" to "m7", "7" to "7",
    "dim7" to "dim7", "hdim7" to "m7b5", "minmaj7" to "m(maj7)", "maj6" to "6", "min6" to "m6", "9" to "9",
    "maj9" to "maj9", "min9" to "m9", "11" to "11", "maj11" to "maj11", "min11" to "m11", "13" to "13",
    "maj13" to "maj13", "min13" to "m13", "sus2" to "sus2", "sus4" to "sus4", "5" to "5", "1" to "(1)",
)
private val SPECIAL_DISPLAY = mapOf(
    "maj(9)" to "add9", "min(9)" to "m(add9)", "sus4(b7)" to "7sus4", "sus4(b7,9)" to "9sus4",
    "sus2(b7)" to "7sus2", "7(#9)" to "7#9", "7(b9)" to "7b9", "7(#11)" to "7#11", "7(b13)" to "7b13",
    "maj6(9)" to "6/9", "5(b7)" to "7(no3)", "aug(b7)" to "aug7",
)

/** Lead-sheet style name: 'A:sus4(b7)' -> 'A7sus4', 'C:maj/3' -> 'C/E', 'N' -> 'N.C.'.
 *  [flats] spells the slash bass; null takes it from how the root is written. */
fun displayName(label: String, flats: Boolean? = null): String {
    val chord = parse(label)
    if (chord.label == NO_CHORD) return "N.C."
    if (chord.root == null) return "?"
    val (body, _, bassPart) = label.trim().partition3('/')
    val (rootText, hasQuality, qualityText) = body.partition3(':')
    val useFlats = flats ?: ('b' in rootText.drop(1))
    val quality = if (hasQuality) qualityText else "maj"
    val suffix = SPECIAL_DISPLAY[quality] ?: run {
        val (shorthand, paren, extra) = quality.partition3('(')
        (DISPLAY_SUFFIX[shorthand] ?: shorthand) + if (paren) "(${extra.dropLast(1)})" else ""
    }
    var text = rootText + suffix
    if (bassPart.isNotEmpty() && chord.bass != chord.root) text += "/" + pcToName(chord.bass!!, useFlats)
    return text
}

// Lead-sheet suffix -> Harte quality.
private val LEADSHEET = mapOf(
    "" to "maj", "M" to "maj", "maj" to "maj", "m" to "min", "min" to "min", "-" to "min", "7" to "7", "dom7" to "7",
    "maj7" to "maj7", "M7" to "maj7", "Δ" to "maj7", "Δ7" to "maj7", "m7" to "min7", "min7" to "min7", "-7" to "min7",
    "dim" to "dim", "°" to "dim", "o" to "dim", "dim7" to "dim7", "°7" to "dim7", "o7" to "dim7", "m7b5" to "hdim7",
    "ø" to "hdim7", "ø7" to "hdim7", "aug" to "aug", "+" to "aug", "6" to "maj6", "m6" to "min6", "9" to "9",
    "maj9" to "maj9", "m9" to "min9", "11" to "11", "m11" to "min11", "13" to "13", "sus2" to "sus2",
    "sus4" to "sus4", "sus" to "sus4", "5" to "5", "add9" to "maj(9)", "madd9" to "min(9)", "m(add9)" to "min(9)",
    "7sus4" to "sus4(b7)", "9sus4" to "sus4(b7,9)", "7#9" to "7(#9)", "7b9" to "7(b9)", "m(maj7)" to "minmaj7",
    "mMaj7" to "minmaj7", "6/9" to "maj6(9)", "aug7" to "aug(b7)",
)
private val SEMITONE_DEGREE = listOf("1", "b2", "2", "b3", "3", "4", "b5", "5", "b6", "6", "b7", "7")

/** Lead-sheet chord name -> Harte label: 'Em' -> 'E:min', 'Bm7' -> 'B:min7', 'C/E' -> 'C:maj/3',
 *  'A7sus4' -> 'A:sus4(b7)', 'N.C.' -> 'N'. Harte labels pass through unchanged. */
fun parseDisplay(name: String): String {
    val text = name.trim()
    if (text.uppercase() in setOf("N", "N.C.", "NC")) return NO_CHORD
    if (':' in text) {
        parse(text)
        return text
    }
    val rootText = rootText(text)
    val root = noteToPc(rootText)
    val (body, slash, bassText) = text.substring(rootText.length).partition3('/')
    val quality = LEADSHEET[body] ?: throw IllegalArgumentException("unknown chord name: '$text'")
    var label = "$rootText:$quality"
    if (slash) {
        if (body == "6" && bassText == "9") return "$rootText:maj6(9)" // 6/9 chord, not a slash chord
        label += "/" + SEMITONE_DEGREE[Math.floorMod(noteToPc(bassText) - root, 12)]
    }
    parse(label)
    return label
}
