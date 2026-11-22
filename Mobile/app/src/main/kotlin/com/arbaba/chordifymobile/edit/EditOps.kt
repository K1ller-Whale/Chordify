package com.arbaba.chordifymobile.edit

import com.arbaba.chordifymobile.engine.analysis.ChordSpan
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.music.transposeLabel
import kotlin.math.abs

// The pure operations behind chord editing (spec §3.3). Every one returns a normalised list:
// sorted, touching, from 0 to the duration, no span shorter than MIN_SPAN_SECONDS and no two
// neighbours with the same label.

/** The shortest chord an edit leaves; a shorter one gives its time to its neighbour. */
const val MIN_SPAN_SECONDS = 0.05

private const val EPS = 1e-9

/** One span per chord of [result], as it stands. */
fun spansOf(result: AnalysisResult): List<ChordSpan> =
    result.chords.map { ChordSpan(it.start, it.end, it.label, it.edited) }

/** [spans] sorted, clamped into [0, duration], with gaps filled by "N", overlaps resolved, short
 *  spans absorbed and equal neighbours merged. */
fun normalise(spans: List<ChordSpan>, duration: Double): List<ChordSpan> {
    val sorted = spans
        .map { s ->
            val start = s.start.coerceIn(0.0, duration)
            s.copy(start = start, end = s.end.coerceIn(start, duration))
        }
        .sortedBy { it.start }
    val filled = ArrayList<ChordSpan>(sorted.size + 2)
    var cursor = 0.0
    for (s in sorted) {
        val start = maxOf(s.start, cursor)
        if (start - cursor > EPS) filled += ChordSpan(cursor, start, "N", edited = true)
        filled += s.copy(start = start, end = maxOf(s.end, start))
        cursor = maxOf(cursor, s.end)
    }
    if (duration - cursor > EPS) filled += ChordSpan(cursor, duration, "N", edited = true)
    // A span shorter than the minimum gives its time to the previous span, or to the next if first.
    var i = 0
    while (i < filled.size && filled.size > 1) {
        val s = filled[i]
        if (s.end - s.start < MIN_SPAN_SECONDS - EPS) {
            if (i > 0) {
                filled[i - 1] = filled[i - 1].copy(end = s.end)
                filled.removeAt(i)
            } else {
                filled[1] = filled[1].copy(start = s.start)
                filled.removeAt(0)
            }
            i = maxOf(i - 1, 0)
        } else {
            i++
        }
    }
    val merged = ArrayList<ChordSpan>(filled.size)
    for (s in filled) {
        val last = merged.lastOrNull()
        if (last != null && last.label == s.label) {
            merged[merged.lastIndex] = last.copy(end = s.end, edited = last.edited || s.edited)
        } else {
            merged += s
        }
    }
    return merged
}

/** Moves the boundary between spans [edge]-1 and [edge] to [time], kept between their outer
 *  edges. A span left with no length disappears. */
fun moveEdge(spans: List<ChordSpan>, edge: Int, time: Double, duration: Double): List<ChordSpan> {
    if (edge < 1 || edge >= spans.size) return spans
    val before = spans[edge - 1]
    val after = spans[edge]
    val at = time.coerceIn(before.start, after.end)
    val out = spans.toMutableList()
    out[edge - 1] = before.copy(end = at, edited = true)
    out[edge] = after.copy(start = at, edited = true)
    return normalise(out, duration)
}

/** Moves the start of span [index]; the first span's start stays 0. */
fun setStart(spans: List<ChordSpan>, index: Int, time: Double, duration: Double): List<ChordSpan> =
    if (index == 0) spans else moveEdge(spans, index, time, duration)

/** Moves the end of span [index]; the last span's end stays the duration. */
fun setEnd(spans: List<ChordSpan>, index: Int, time: Double, duration: Double): List<ChordSpan> =
    if (index == spans.lastIndex) spans else moveEdge(spans, index + 1, time, duration)

/** Cuts span [index] in two at [time]; nothing happens within MIN_SPAN_SECONDS of either end. */
fun split(spans: List<ChordSpan>, index: Int, time: Double, duration: Double): List<ChordSpan> {
    val s = spans.getOrNull(index) ?: return spans
    if (!(s.start + MIN_SPAN_SECONDS < time && time < s.end - MIN_SPAN_SECONDS)) return spans
    val out = spans.toMutableList()
    out[index] = s.copy(end = time, edited = true)
    out.add(index + 1, s.copy(start = time, edited = true))
    return out
}

/** Span [index] takes over the next span and keeps its own label; nothing happens for the last. */
fun mergeWithNext(spans: List<ChordSpan>, index: Int, duration: Double): List<ChordSpan> {
    if (index < 0 || index >= spans.lastIndex) return spans
    val out = spans.toMutableList()
    out[index] = spans[index].copy(end = spans[index + 1].end, edited = true)
    out.removeAt(index + 1)
    return normalise(out, duration)
}

/** Gives span [index] the Harte label [label] (as it sounds); a neighbour with it merges in. */
fun rename(spans: List<ChordSpan>, index: Int, label: String, duration: Double): List<ChordSpan> {
    if (index !in spans.indices) return spans
    val out = spans.toMutableList()
    out[index] = spans[index].copy(label = label, edited = true)
    return normalise(out, duration)
}

/** The nearest of [beats] if within [tolerance] seconds of [time], else [time]. */
fun snapToBeat(time: Double, beats: List<Double>, tolerance: Double): Double {
    val nearest = beats.minByOrNull { abs(it - time) } ?: return time
    return if (abs(nearest - time) <= tolerance) nearest else time
}

/** The last beat before [time] for a negative [delta], the first after it for a positive one;
 *  half a second away when there is none. */
fun stepToBeat(time: Double, beats: List<Double>, delta: Int): Double =
    if (delta < 0) beats.lastOrNull { it < time - 1e-6 } ?: (time - 0.5)
    else beats.firstOrNull { it > time + 1e-6 } ?: (time + 0.5)

/** The label as it sounds, for a [shapeLabel] played with the capo on fret [capo]. */
fun soundingLabel(shapeLabel: String, capo: Int, flats: Boolean): String = transposeLabel(shapeLabel, capo, flats)

/** The label of the shape to play, for a [soundingLabel] with the capo on fret [capo]; "N" stays "N". */
fun shapeLabelOf(soundingLabel: String, capo: Int, flats: Boolean): String = transposeLabel(soundingLabel, -capo, flats)
