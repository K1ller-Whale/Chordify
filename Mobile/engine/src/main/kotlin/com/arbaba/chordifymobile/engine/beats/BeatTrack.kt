package com.arbaba.chordifymobile.engine.beats

import com.arbaba.chordifymobile.engine.util.pyRoundToInt
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.log2
import kotlin.math.sqrt

// Port of librosa 1.0.1's beat_track(onset_envelope=..., sr=22050, hop_length=512, trim=False):
// tempo from the mean autocorrelation tempogram with a log-normal prior around 120 bpm, then
// Ellis's dynamic-programming beat tracker. The float32/float64 split follows librosa's numba
// kernels, so the beat frames come out identical for the same onset envelope.

private const val FRAME_RATE = ONSET_SAMPLE_RATE.toDouble() / ONSET_HOP
private const val TEMPOGRAM_WINDOW = 344 // librosa.time_to_frames(8.0, sr=22050, hop_length=512)
private const val START_BPM = 120.0
private const val MAX_TEMPO = 320.0
private const val TIGHTNESS = 100.0

/** librosa.feature.tempogram(onset_envelope, win_length=344).mean(axis=1): the autocorrelation
 *  of a Hann-windowed stretch of onsets around every frame, each normalised by its peak, averaged. */
fun tempogramMean(onset: FloatArray): DoubleArray {
    val n = onset.size
    val half = TEMPOGRAM_WINDOW / 2
    // np.pad(mode="linear_ramp", end_values=0): ramps from 0 up to the edge values, in float32
    val padded = FloatArray(n + TEMPOGRAM_WINDOW)
    val left = onset.firstOrNull() ?: 0f
    val right = onset.lastOrNull() ?: 0f
    for (k in 0 until half) {
        padded[k] = (k * (left.toDouble() / half)).toFloat()
        padded[n + half + k] = ((half - 1 - k) * (right.toDouble() / half)).toFloat()
    }
    onset.copyInto(padded, half)
    val window = hann(TEMPOGRAM_WINDOW)
    val fft = Fft(1024) // at least 2 × 344 - 1, so the circular autocorrelation is the linear one
    val re = DoubleArray(1024)
    val im = DoubleArray(1024)
    val sum = DoubleArray(TEMPOGRAM_WINDOW)
    for (t in 0 until n) {
        for (i in 0 until 1024) {
            re[i] = if (i < TEMPOGRAM_WINDOW) padded[t + i] * window[i] else 0.0
            im[i] = 0.0
        }
        fft.transform(re, im)
        for (i in 0 until 1024) {
            re[i] = re[i] * re[i] + im[i] * im[i]
            im[i] = 0.0
        }
        fft.transform(re, im, inverse = true)
        var peak = 0.0
        for (lag in 0 until TEMPOGRAM_WINDOW) peak = maxOf(peak, abs(re[lag]))
        if (peak < java.lang.Double.MIN_NORMAL) peak = 1.0 // util.normalize leaves an all-zero column as it is
        for (lag in 0 until TEMPOGRAM_WINDOW) sum[lag] += re[lag] / peak
    }
    return DoubleArray(TEMPOGRAM_WINDOW) { sum[it] / n }
}

/** librosa.feature.tempo(onset_envelope, sr=22050, hop_length=512): the tempogram bin that best
 *  balances periodicity against a log-normal prior around 120 bpm, below 320 bpm. */
fun estimateTempo(onset: FloatArray): Double {
    val tempogram = tempogramMean(onset)
    val bpms = DoubleArray(TEMPOGRAM_WINDOW) { if (it == 0) Double.POSITIVE_INFINITY else 60.0 * ONSET_SAMPLE_RATE / (ONSET_HOP * it.toDouble()) }
    val fastest = bpms.indexOfFirst { it < MAX_TEMPO }
    var best = 0
    var bestScore = Double.NEGATIVE_INFINITY
    for (i in bpms.indices) {
        val prior = if (i < fastest) Double.NEGATIVE_INFINITY else -0.5 * (log2(bpms[i]) - log2(START_BPM)).let { it * it }
        val score = ln1p(1e6 * tempogram[i]) + prior
        if (score > bestScore) {
            best = i
            bestScore = score
        }
    }
    return bpms[best]
}

/** The beat frames librosa.beat.beat_track(..., trim=False) finds at [bpm]. */
fun trackBeatFrames(onset: FloatArray, bpm: Double): IntArray {
    val n = onset.size
    val framesPerBeat = Math.rint(FRAME_RATE * 60.0 / bpm)

    // __normalize_onsets: divide by the standard deviation (ddof 1), in float32
    val mean = onset.sumOf { it.toDouble() } / n
    val norm = sqrt(onset.sumOf { (it - mean) * (it - mean) } / (n - 1)).toFloat()
    val normalised = FloatArray(n) { onset[it] / (norm + java.lang.Float.MIN_NORMAL) }

    // __beat_local_score: a Gaussian window of ±1 beat, accumulated in float32 as numba does
    val fpb = framesPerBeat.toInt()
    val kernel = DoubleArray(2 * fpb + 1) { k -> exp(-0.5 * ((k - framesPerBeat) * 32.0 / framesPerBeat).let { it * it }) }
    val size = kernel.size
    val local = FloatArray(n) { i ->
        var acc = 0f
        for (k in maxOf(0, i + size / 2 - n + 1) until minOf(i + size / 2, size)) {
            acc = (acc.toDouble() + kernel[k] * normalised[i + size / 2 - k]).toFloat()
        }
        acc
    }

    // __beat_track_dp, in float64
    val threshold = 0.01 * (local.maxOrNull() ?: 0f)
    val backlink = IntArray(n)
    val cumscore = DoubleArray(n)
    var firstBeat = true
    val logFpb = ln(framesPerBeat)
    val nearest = pyRoundToInt(framesPerBeat / 2)
    for (i in 0 until n) {
        var bestScore = Double.NEGATIVE_INFINITY
        var location = -1
        var loc = i - nearest
        val stop = (i - 2 * framesPerBeat - 1).toInt()
        while (loc > stop) {
            if (loc < 0) break
            val gap = ln((i - loc).toDouble()) - logFpb
            val score = cumscore[loc] - TIGHTNESS * gap * gap
            if (score > bestScore) {
                bestScore = score
                location = loc
            }
            loc--
        }
        cumscore[i] = if (location >= 0) local[i] + bestScore else local[i].toDouble()
        if (firstBeat && local[i] < threshold) {
            backlink[i] = -1
        } else {
            backlink[i] = location
            firstBeat = false
        }
    }

    // __last_beat: the last local maximum of the cumulative score above half their median
    val peaks = BooleanArray(n) { i ->
        when {
            n < 2 -> false
            i == 0 -> false
            i == n - 1 -> cumscore[i] > cumscore[i - 1]
            else -> cumscore[i] > cumscore[i - 1] && cumscore[i] >= cumscore[i + 1]
        }
    }
    val peakScores = cumscore.filterIndexed { i, _ -> peaks[i] }.sorted()
    var tail = n - 1
    if (peakScores.isNotEmpty()) {
        val middle = peakScores.size / 2
        val median = if (peakScores.size % 2 == 1) peakScores[middle] else (peakScores[middle - 1] + peakScores[middle]) / 2
        for (i in n - 1 downTo 0) {
            if (peaks[i] && cumscore[i] >= 0.5 * median) {
                tail = i
                break
            }
        }
    }

    // __dp_backtrack, then __trim_beats with trim=False: only frames with no local score at the
    // ends are dropped
    val beats = BooleanArray(n)
    var at = tail
    while (at >= 0) {
        beats[at] = true
        at = backlink[at]
    }
    var start = 0
    while (start < n && local[start] <= 0f) beats[start++] = false
    var end = n - 1
    while (end >= 0 && local[end] <= 0f) beats[end--] = false
    return beats.indices.filter { beats[it] }.toIntArray()
}

/** librosa.beat.beat_track(onset_envelope=onset, sr=22050, hop_length=512, trim=False):
 *  (tempo in bpm, beat frames); (0.0, no beats) for an envelope of zeros. */
fun beatTrack(onset: FloatArray): Pair<Double, IntArray> {
    if (onset.none { it != 0f }) return 0.0 to IntArray(0)
    val bpm = estimateTempo(onset)
    return bpm to trackBeatFrames(onset, bpm)
}
