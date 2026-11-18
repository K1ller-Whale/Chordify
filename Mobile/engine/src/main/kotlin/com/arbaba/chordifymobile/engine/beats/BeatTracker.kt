package com.arbaba.chordifymobile.engine.beats

import com.arbaba.chordifymobile.engine.audio.ANALYSIS_SAMPLE_RATE
import com.arbaba.chordifymobile.engine.audio.Resampler
import com.arbaba.chordifymobile.engine.audio.SampleSink

/** Beat tracking as the server does it, as a sink of the 44.1 kHz stream: resampled to
 *  22.05 kHz with libsoxr (librosa.resample), onset strength, then [trackBeats] in [finish]. */
class BeatTracker : SampleSink, AutoCloseable {
    private val resampler = Resampler(ANALYSIS_SAMPLE_RATE, ONSET_SAMPLE_RATE)
    private val onset = OnsetStrength()

    override fun write(samples: FloatArray, count: Int) {
        val resampled = resampler.process(samples, count)
        onset.write(resampled, resampled.size)
    }

    /** The onset envelope and the beats. Call once, after the last [write]. */
    fun finish(): Pair<FloatArray, BeatInfo> {
        val rest = resampler.finish()
        onset.write(rest, rest.size)
        val envelope = onset.finish()
        return envelope to trackBeats(envelope)
    }

    override fun close() = resampler.close()
}
