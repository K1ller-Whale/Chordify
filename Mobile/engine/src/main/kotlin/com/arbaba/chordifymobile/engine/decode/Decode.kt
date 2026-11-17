package com.arbaba.chordifymobile.engine.decode

import com.arbaba.chordifymobile.engine.util.median
import com.arbaba.chordifymobile.engine.util.pyRoundToInt
import com.arbaba.chordifymobile.engine.util.searchSorted
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// Port of chordify_core/decode.py: from frame posteriors to a chord timeline.
//  1. Pool frame log-posteriors per beat (chords change on beats).
//  2. Divide by class priors (hybrid NN/HMM scaling) so frequent classes are not favoured.
//  3. Viterbi with a per-step probability of staying (from the boundary head or a prior) and a
//     chord-to-chord matrix for changes (from the progression model).
//  4. Group into segments with confidence and alternatives, merge fragments.
// Posteriors are (frames, classes) arrays of doubles, as numpy float64 arrays are in Python.

internal const val EPS = 1e-9

/** A run of frames [start, stop), like a Python slice. */
data class Span(val start: Int, val stop: Int)

/** Boundaries of decoding units: beat intervals (optionally subdivided), or frames.
 *
 *  Before the first beat, after the last one and across gaps longer than 1.5 beats, the grid
 *  continues at the median beat period, so an intro, a break or a fade-out without beats is not
 *  decoded as one long unit. */
fun unitBoundaries(duration: Double, beats: DoubleArray?, frameRate: Double, subdivide: Int = 1): DoubleArray {
    if (beats == null || beats.size < 2) {
        val n = ceil(duration * frameRate).toInt()
        return DoubleArray(n + 1) { it / frameRate }
    }
    var grid = beats.filter { it > 0 && it < duration }.toDoubleArray()
    if (grid.size >= 2) {
        val period = median(DoubleArray(grid.size - 1) { grid[it + 1] - grid[it] })
        if (period > 0) {
            val head = arange(grid[0] - period, 0.0, -period).reversedArray()
            val tail = arange(grid.last() + period, duration, period)
            val gaps = ArrayList<Double>()
            for (i in 0 until grid.size - 1) {
                val a = grid[i]
                val b = grid[i + 1]
                if (b - a > 1.5 * period) {
                    val points = linspace(a, b, pyRoundToInt((b - a) / period) + 1)
                    for (k in 1 until points.size - 1) gaps.add(points[k])
                }
            }
            grid = (head + grid + tail + gaps.toDoubleArray()).distinct().sorted().toDoubleArray()
        }
    }
    val points = ArrayList<Double>()
    points.add(0.0)
    for (i in 0 until grid.size - 1) {
        val a = grid[i]
        val b = grid[i + 1]
        for (s in 0 until subdivide) points.add(a + (b - a) * s / subdivide)
    }
    points.add(grid.last())
    if (duration - grid.last() > 1e-3) points.add(duration)
    // numpy.round(points, 6), then numpy.unique
    var result = points.map { Math.rint(it * 1e6) / 1e6 }.distinct().sorted().toDoubleArray()
    if (result[0] > 0) result = doubleArrayOf(0.0) + result
    return result
}

/** Mean log-probability per unit; units that contain no frame centre borrow the nearest frame.
 *  Frame i is centred at `frameOffset + i / frameRate` (default: the middle of [i, i+1) / fps). */
fun pool(logProbs: Array<DoubleArray>, frameRate: Double, boundaries: DoubleArray,
         frameOffset: Double? = null): Pair<Array<DoubleArray>, List<Span>> {
    val nFrames = logProbs.size
    val offset = frameOffset ?: (0.5 / frameRate)
    val centres = DoubleArray(nFrames) { offset + it / frameRate }
    val pooled = ArrayList<DoubleArray>()
    val spans = ArrayList<Span>()
    for (u in 0 until boundaries.size - 1) {
        var s = searchSorted(centres, boundaries[u])
        var e = searchSorted(centres, boundaries[u + 1])
        if (e <= s) {
            s = min(s, nFrames - 1)
            e = s + 1
        }
        spans.add(Span(s, e))
        pooled.add(meanRows(logProbs, s, e))
    }
    return pooled.toTypedArray() to spans
}

/** Log-linear pooling of the prior stay probability and the boundary head:
 *  stay ∝ selfProb^(1-w) · (1-p)^w, change ∝ (1-selfProb)^(1-w) · p^w. */
fun combineStay(selfProb: Double, pChange: Double, weight: Double): Double {
    val stay = selfProb.pow(1 - weight) * (1 - pChange).pow(weight)
    val change = (1 - selfProb).pow(1 - weight) * pChange.pow(weight)
    return stay / (stay + change)
}

fun uniformChangeMatrix(nClasses: Int): Array<DoubleArray> =
    Array(nClasses) { i -> DoubleArray(nClasses) { j -> if (i == j) 0.0 else 1.0 / (nClasses - 1) } }

/** Best state path. `stay[t]` is P(no change entering step t); a change goes i -> j with
 *  probability `(1 - stay[t]) * changeMatrix[i][j]` (rows sum to 1, zero diagonal). */
fun viterbi(logEmissions: Array<DoubleArray>, stay: DoubleArray, changeMatrix: Array<DoubleArray>): IntArray {
    val nSteps = logEmissions.size
    val nStates = logEmissions[0].size
    val logChange = Array(nStates) { i ->
        DoubleArray(nStates) { j -> if (i == j) Double.NEGATIVE_INFINITY else ln(changeMatrix[i][j] + EPS) }
    }
    var score = DoubleArray(nStates) { logEmissions[0][it] - ln(nStates.toDouble()) }
    val back = Array(nSteps) { IntArray(nStates) }
    for (t in 1 until nSteps) {
        val s = stay[t].coerceIn(1e-6, 1 - 1e-6)
        val logStay = ln(s)
        val logMove = ln(1 - s)
        val next = DoubleArray(nStates)
        for (j in 0 until nStates) {
            var bestFrom = 0
            var bestChange = score[0] + logMove + logChange[0][j]
            for (i in 1 until nStates) {
                val candidate = score[i] + logMove + logChange[i][j]
                if (candidate > bestChange) {
                    bestChange = candidate
                    bestFrom = i
                }
            }
            val stayScore = score[j] + logStay
            if (stayScore >= bestChange) {
                back[t][j] = j
                next[j] = stayScore + logEmissions[t][j]
            } else {
                back[t][j] = bestFrom
                next[j] = bestChange + logEmissions[t][j]
            }
        }
        score = next
    }
    val path = IntArray(nSteps)
    var best = 0
    for (j in 1 until nStates) if (score[j] > score[best]) best = j
    path[nSteps - 1] = best
    for (t in nSteps - 1 downTo 1) path[t - 1] = back[t][path[t]]
    return path
}

/** Decode (T, V) frame posteriors into chord segments.
 *
 *  [changeProb] (T) is the boundary head's P(chord change at frame t); when given, each unit's
 *  stay probability pools `1 - max(changeProb near its start)` with [selfProb] (see
 *  [combineStay]). [changeMatrix] (V, V) is the transition prior for changes (e.g. from the
 *  progression model); uniform when null. [frameOffset] is the centre time of frame 0
 *  (`FeatureSpec.offset`); [duration] the audio length (default: the span of the frames). */
fun decode(
    posteriors: Array<DoubleArray>,
    frameRate: Double,
    beats: DoubleArray? = null,
    changeProb: DoubleArray? = null,
    prior: DoubleArray? = null,
    alpha: Double = 0.5,
    selfProb: Double? = null,
    changeMatrix: Array<DoubleArray>? = null,
    subdivide: Int = 1,
    minUnits: Int = 1,
    nAlternatives: Int = 3,
    boundaryWeight: Double = 0.5,
    frameOffset: Double? = null,
    duration: Double? = null,
): List<Segment> {
    val nFrames = posteriors.size
    val nClasses = posteriors[0].size
    val length = duration ?: (nFrames / frameRate + (if (frameOffset == null) 0.0 else frameOffset - 0.5 / frameRate))
    val logProbs = Array(nFrames) { t -> DoubleArray(nClasses) { ln(posteriors[t][it] + EPS) } }
    val boundaries = unitBoundaries(length, beats, frameRate, subdivide)
    val (pooled, spans) = pool(logProbs, frameRate, boundaries, frameOffset)
    if (prior != null) {
        for (row in pooled) for (c in row.indices) row[c] -= alpha * ln(prior[c] + EPS)
    }
    val unitsAreFrames = beats == null || beats.size < 2
    val stayProb = selfProb ?: if (unitsAreFrames) 0.97 else 0.75
    val stay = DoubleArray(spans.size) { stayProb }
    if (changeProb != null) {
        val clipped = DoubleArray(changeProb.size) { changeProb[it].coerceIn(1e-4, 1 - 1e-4) }
        for ((u, span) in spans.withIndex()) {
            val lo = max(0, span.start - 1)
            val hi = min(nFrames, span.start + 2)
            stay[u] = combineStay(stayProb, (lo until hi).maxOf { clipped[it] }, boundaryWeight)
        }
    }
    val path = viterbi(pooled, stay, changeMatrix ?: uniformChangeMatrix(nClasses))
    val segments = toSegments(path, boundaries, spans, posteriors, nAlternatives)
    return mergeShort(segments, boundaries, minUnits, posteriors, frameRate, nAlternatives, frameOffset)
}

/** numpy's `arange(start, stop, step)` for doubles, filled the way numpy fills it. */
private fun arange(start: Double, stop: Double, step: Double): DoubleArray {
    val n = ceil((stop - start) / step).toInt()
    if (n <= 0) return DoubleArray(0)
    val out = DoubleArray(n)
    out[0] = start
    if (n > 1) {
        out[1] = start + step
        val delta = out[1] - out[0]
        for (i in 2 until n) out[i] = start + i * delta
    }
    return out
}

/** numpy's `linspace(start, stop, num)`: `i * step + start`, with the last value exactly `stop`. */
private fun linspace(start: Double, stop: Double, num: Int): DoubleArray {
    val step = (stop - start) / (num - 1)
    return DoubleArray(num) { if (it == num - 1) stop else it * step + start }
}
