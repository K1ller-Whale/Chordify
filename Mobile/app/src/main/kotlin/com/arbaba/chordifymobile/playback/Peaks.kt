package com.arbaba.chordifymobile.playback

import com.arbaba.chordifymobile.engine.audio.ANALYSIS_SAMPLE_RATE
import com.arbaba.chordifymobile.engine.audio.AudioSource
import com.arbaba.chordifymobile.engine.audio.SampleSink
import com.arbaba.chordifymobile.engine.audio.streamAudio
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// The waveform the timeline draws (chordify-frontend/src/lib/audio.ts waveformPeaks): the peak
// amplitude per step of max(0.05, duration / 3000) seconds, scaled to the loudest. The web reads
// the first channel at the file's own rate; the engine decodes the mono mix at 44.1 kHz.

/** The finest step, 0.05 s; longer songs merge whole groups of it. */
const val PEAK_HOP_SECONDS = 0.05

/** The web's waveform step for a song of [duration] seconds. */
fun peakStep(duration: Double): Double = max(PEAK_HOP_SECONDS, duration / 3000)

/** How many 0.05 s peaks make one step of the web's waveform. */
fun mergeFactor(duration: Double): Int = max(1, (peakStep(duration) / PEAK_HOP_SECONDS).roundToInt())

/** Keeps the peak of every [hopSamples] samples. */
class PeakSink(private val hopSamples: Int) : SampleSink {
    private var peaks = FloatArray(1024)
    private var size = 0
    private var current = 0f
    private var filled = 0

    override fun write(samples: FloatArray, count: Int) {
        for (i in 0 until count) {
            val amplitude = abs(samples[i])
            if (amplitude > current) current = amplitude
            if (++filled == hopSamples) push()
        }
    }

    private fun push() {
        if (size == peaks.size) peaks = peaks.copyOf(size * 2)
        peaks[size++] = current
        current = 0f
        filled = 0
    }

    /** The peaks, with the last partial hop. */
    fun finish(): FloatArray {
        if (filled > 0) push()
        return peaks.copyOf(size)
    }
}

/** The maximum of every [factor] peaks, scaled so that the loudest is 1. */
fun reducePeaks(peaks: FloatArray, factor: Int): FloatArray {
    val merged = FloatArray((peaks.size + factor - 1) / factor) { group ->
        var peak = 0f
        for (i in group * factor until minOf(peaks.size, (group + 1) * factor)) peak = max(peak, peaks[i])
        peak
    }
    val loudest = merged.maxOrNull() ?: 0f
    return if (loudest > 0f) FloatArray(merged.size) { merged[it] / loudest } else merged
}

/** Decodes [source] once and returns its waveform for a song of [duration] seconds. */
fun decodePeaks(source: AudioSource, duration: Double, isCancelled: () -> Boolean = { false }): FloatArray {
    val sink = PeakSink((ANALYSIS_SAMPLE_RATE * PEAK_HOP_SECONDS).roundToInt())
    streamAudio(source, listOf(sink), isCancelled = isCancelled)
    return reducePeaks(sink.finish(), mergeFactor(duration))
}
