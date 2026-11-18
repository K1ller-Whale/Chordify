package com.arbaba.chordifymobile.engine.beats

import com.arbaba.chordifymobile.engine.util.pyRound
import kotlin.math.sqrt

/** chordify_backend.app.pipeline.beats.BeatInfo: beat times (s), tempo, and how regular the
 *  inter-beat intervals are (0..1). */
class BeatInfo(val beats: DoubleArray, val bpm: Double?, val confidence: Double) {
    /** Enough regular beats to decode chords on them. */
    val usable: Boolean get() = beats.size >= 8 && confidence >= 0.5
}

/** The rest of track_beats once the onset envelope is known: beats, rounded tempo, regularity. */
fun trackBeats(onset: FloatArray): BeatInfo {
    val (tempo, frames) = beatTrack(onset)
    val beats = DoubleArray(frames.size) { frames[it] * ONSET_HOP / ONSET_SAMPLE_RATE.toDouble() }
    if (beats.size < 4) return BeatInfo(beats, null, 0.0)
    val intervals = DoubleArray(beats.size - 1) { beats[it + 1] - beats[it] }
    val mean = intervals.average()
    val std = sqrt(intervals.sumOf { (it - mean) * (it - mean) } / intervals.size)
    val confidence = (1.0 - std / maxOf(mean, 1e-6)).coerceIn(0.0, 1.0)
    return BeatInfo(beats, pyRound(tempo, 1), pyRound(confidence, 3))
}
