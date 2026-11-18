package com.arbaba.chordifymobile.music

import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ChordSegment

// Port of chordify-frontend/src/lib/capo.ts. With a capo on fret N, every chord is played with
// the shape of the chord N semitones lower: a song in Ab with the capo on 1 is played with G
// shapes. The app shows those shapes; Roman numerals and functions are relative to the key, so
// they do not change.

const val MAX_CAPO = 9

private val SHARP_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val FLAT_NAMES = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

// The same key signatures as the server (chordify_core/theory.py): F# major, not Gb
private val FLAT_MAJOR = setOf(5, 10, 3, 8, 1)
private val FLAT_MINOR = setOf(2, 7, 0, 5, 10, 3)

private val NOTE = Regex("^([A-G][#b]*)")
private val DISPLAY = Regex("^([A-G][#b]*)(.*?)(?:/([A-G][#b]*))?$")

private fun pcName(pc: Int, flats: Boolean) = (if (flats) FLAT_NAMES else SHARP_NAMES)[Math.floorMod(pc, 12)]

/** The key the shapes are in: the song's key moved down by the capo, spelled as usual. */
data class ShapeKey(val tonic: String, val mode: String, val flats: Boolean)

fun shapeKey(tonic: String, mode: String, capo: Int): ShapeKey {
    val pc = Math.floorMod(noteToPc(tonic) - capo, 12)
    val flats = pc in (if (mode == "minor") FLAT_MINOR else FLAT_MAJOR)
    return ShapeKey(pcName(pc, flats), mode, flats)
}

fun transposeNote(name: String, semitones: Int, flats: Boolean): String = pcName(noteToPc(name) + semitones, flats)

/** "Cmaj7/E" -> "Bmaj7/D#" for -1; the suffix is kept, "N.C." is left alone. */
fun transposeDisplay(display: String, semitones: Int, flats: Boolean): String {
    val match = DISPLAY.find(display) ?: return display
    val root = match.groupValues[1]
    val suffix = match.groupValues[2]
    val bass = match.groups[3]?.value
    return transposeNote(root, semitones, flats) + suffix + (if (bass.isNullOrEmpty()) "" else "/" + transposeNote(bass, semitones, flats))
}

/** Harte labels: "C:maj7/3" -> "B:maj7/3" (the bass is an interval, so it stays). */
fun transposeLabel(label: String, semitones: Int, flats: Boolean): String {
    val match = NOTE.find(label) ?: return label
    val root = match.groupValues[1]
    return transposeNote(root, semitones, flats) + label.substring(root.length)
}

private fun transposeChord(chord: ChordSegment, shift: Int, flats: Boolean): ChordSegment = chord.copy(
    label = transposeLabel(chord.label, shift, flats),
    display = transposeDisplay(chord.display, shift, flats),
    root = chord.root?.takeIf { it.isNotEmpty() }?.let { transposeNote(it, shift, flats) } ?: chord.root,
    bass = chord.bass?.takeIf { it.isNotEmpty() }?.let { transposeNote(it, shift, flats) } ?: chord.bass,
    // "Ab Mixolydian" -> "G Mixolydian": the leading note moves like a label's root
    scaleHint = chord.scaleHint?.takeIf { it.isNotEmpty() }?.let { transposeLabel(it, shift, flats) } ?: chord.scaleHint,
    alternatives = chord.alternatives.map {
        it.copy(label = transposeLabel(it.label, shift, flats), display = transposeDisplay(it.display, shift, flats))
    },
    next = chord.next.map {
        it.copy(label = transposeLabel(it.label, shift, flats), display = transposeDisplay(it.display, shift, flats))
    },
)

/** The analysis as it looks with the capo on fret [capo]: every chord name is the shape to play. */
fun withCapo(result: AnalysisResult, capo: Int): AnalysisResult {
    if (capo == 0) return result
    val global = result.key.global
    val key = shapeKey(global.tonic, global.mode, capo)
    val shift = -capo
    fun display(name: String) = transposeDisplay(name, shift, key.flats)
    return result.copy(
        key = result.key.copy(
            global = global.copy(tonic = key.tonic),
            segments = result.key.segments.map { it.copy(tonic = transposeNote(it.tonic, shift, key.flats)) },
        ),
        chords = result.chords.map { transposeChord(it, shift, key.flats) },
        bars = result.bars.map { bar -> bar.copy(chords = bar.chords.map(::display)) },
        summary = result.summary.copy(timeShare = result.summary.timeShare.map { it.copy(display = display(it.display)) }),
    )
}

data class CapoSuggestion(val capo: Int, val open: Double)

/** Share of the chord time (N.C. excluded) that has an open-position shape with the capo on [capo]. */
fun openShare(chords: List<ChordSegment>, capo: Int): Double {
    var open = 0.0
    var total = 0.0
    for (chord in chords) {
        val root = chord.root
        val quality = chord.quality
        if (root.isNullOrEmpty() || quality.isNullOrEmpty()) continue
        val length = chord.end - chord.start
        total += length
        if (hasOpenShape(Math.floorMod(noteToPc(root) - capo, 12), quality)) open += length
    }
    return if (total != 0.0) open / total else 0.0
}

/** The capo position (0-7) whose shapes are most often open chords, the lowest on a tie. No
 *  capo unless one gives at least 10% more of the song in open chords. */
fun suggestCapo(chords: List<ChordSegment>): CapoSuggestion {
    val none = CapoSuggestion(0, openShare(chords, 0))
    var best = none
    for (capo in 1..7) {
        val open = openShare(chords, capo)
        if (open > best.open + 1e-9) best = CapoSuggestion(capo, open)
    }
    return if (best.open >= none.open + 0.1) best else none
}
