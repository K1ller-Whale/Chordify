package com.arbaba.chordifymobile.engine.decode

import com.arbaba.chordifymobile.engine.util.argmax
import com.arbaba.chordifymobile.engine.util.pyRoundToInt
import com.arbaba.chordifymobile.engine.util.searchSorted
import com.arbaba.chordifymobile.engine.vocab.withBass
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Chord segments from chordify_core/decode.py: grouping decoded units, merging fragments.

data class Segment(
    val start: Double,
    val end: Double,
    val index: Int, // vocabulary class
    val confidence: Double, // mean frame posterior of the chosen class
    val alternatives: List<Pair<Int, Double>> = emptyList(),
) {
    val duration: Double get() = end - start
}

/** Mean of the frames whose centres fall in [start, end); the nearest frame when none does. */
fun meanOver(posteriors: Array<DoubleArray>, frameTimes: DoubleArray, start: Double, end: Double): DoubleArray {
    val inside = frameTimes.indices.filter { frameTimes[it] >= start && frameTimes[it] < end }
    if (inside.isNotEmpty()) {
        val out = DoubleArray(posteriors[0].size)
        for (t in inside) for (c in out.indices) out[c] += posteriors[t][c]
        for (c in out.indices) out[c] /= inside.size.toDouble()
        return out
    }
    val middle = (start + end) / 2
    var nearest = 0
    for (t in 1 until frameTimes.size) {
        if (abs(frameTimes[t] - middle) < abs(frameTimes[nearest] - middle)) nearest = t
    }
    return posteriors[nearest].copyOf()
}

/** Add a slash bass from the bass head (12 pitch classes + 'none'): when its most likely note is a
 *  chord tone other than the root with probability >= [threshold]. */
fun inversion(label: String, bassProbs: DoubleArray, threshold: Double): String {
    val pc = argmax(bassProbs, until = 12)
    return if (bassProbs[pc] < threshold) label else withBass(label, pc)
}

/** numpy's `rows[from:until].mean(axis=0)`. */
internal fun meanRows(rows: Array<DoubleArray>, from: Int, until: Int): DoubleArray {
    val out = DoubleArray(rows[from].size)
    for (r in from until until) {
        val row = rows[r]
        for (c in out.indices) out[c] += row[c]
    }
    val n = (until - from).toDouble()
    for (c in out.indices) out[c] /= n
    return out
}

/** numpy's `posteriors[span, column].mean()`. */
private fun columnMean(posteriors: Array<DoubleArray>, span: Span, column: Int): Double {
    var sum = 0.0
    for (r in span.start until span.stop) sum += posteriors[r][column]
    return sum / (span.stop - span.start)
}

/** Confidence of [index] over [frames], and the [nAlternatives] most likely other classes. */
internal fun segmentStats(posteriors: Array<DoubleArray>, frames: Span, index: Int,
                          nAlternatives: Int): Pair<Double, List<Pair<Int, Double>>> {
    val mean = meanRows(posteriors, frames.start, frames.stop)
    // numpy's argsort(mean)[::-1]: ascending (equal values in index order), then reversed
    val order = mean.indices.sortedWith(compareBy<Int>({ mean[it] }, { it })).reversed()
        .filter { it != index }.take(nAlternatives)
    return mean[index] to order.map { it to mean[it] }
}

internal fun toSegments(path: IntArray, boundaries: DoubleArray, spans: List<Span>, posteriors: Array<DoubleArray>,
                        nAlternatives: Int): List<Segment> {
    val segments = ArrayList<Segment>()
    var start = 0
    for (u in 1..path.size) {
        if (u == path.size || path[u] != path[start]) {
            val frames = Span(spans[start].start, spans[u - 1].stop)
            val (confidence, alternatives) = segmentStats(posteriors, frames, path[start], nAlternatives)
            segments.add(Segment(boundaries[start], boundaries[u], path[start], confidence, alternatives))
            start = u
        }
    }
    return segments
}

/** Absorb segments shorter than [minUnits] units into the neighbour whose class has more
 *  posterior mass over the fragment. */
fun mergeShort(segments: List<Segment>, boundaries: DoubleArray, minUnits: Int, posteriors: Array<DoubleArray>,
               frameRate: Double, nAlternatives: Int = 3, frameOffset: Double? = null): List<Segment> {
    if (minUnits <= 1 || segments.size < 2) return segments
    fun units(seg: Segment): Int = searchSorted(boundaries, seg.end - 1e-6) - searchSorted(boundaries, seg.start - 1e-6)
    val offset = frameOffset ?: (0.5 / frameRate)
    val last = posteriors.size - 1
    val frames = { start: Double, end: Double ->
        val first = min(last, max(0, pyRoundToInt((start - offset) * frameRate + 0.5)))
        Span(first, min(last + 1, max(first + 1, pyRoundToInt((end - offset) * frameRate + 0.5))))
    }
    var out = segments.toMutableList()
    var changed = true
    while (changed && out.size > 1) {
        changed = false
        for (i in out.indices) {
            val seg = out[i]
            if (units(seg) >= minUnits) continue
            val span = frames(seg.start, seg.end)
            val j = listOf(i - 1, i + 1).filter { it in out.indices }.maxBy { columnMean(posteriors, span, out[it].index) }
            val keep = out[j]
            val start = min(keep.start, seg.start)
            val end = max(keep.end, seg.end)
            val (confidence, alternatives) = segmentStats(posteriors, frames(start, end), keep.index, nAlternatives)
            out[j] = Segment(start, end, keep.index, confidence, alternatives)
            out.removeAt(i)
            out = coalesce(out, posteriors, frames, nAlternatives)
            changed = true
            break
        }
    }
    return out
}

/** Join neighbours that ended up with the same chord after a merge. */
private fun coalesce(segments: List<Segment>, posteriors: Array<DoubleArray>, frames: (Double, Double) -> Span,
                     nAlternatives: Int): MutableList<Segment> {
    val out = ArrayList<Segment>()
    for (seg in segments) {
        val previous = out.lastOrNull()
        if (previous != null && previous.index == seg.index) {
            val (confidence, alternatives) = segmentStats(posteriors, frames(previous.start, seg.end), seg.index, nAlternatives)
            out[out.size - 1] = Segment(previous.start, seg.end, seg.index, confidence, alternatives)
        } else {
            out.add(seg)
        }
    }
    return out
}
