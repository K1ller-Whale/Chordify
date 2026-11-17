package com.arbaba.chordifymobile.engine.audio

import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import java.util.Locale

/** Every analysis runs at 44.1 kHz, the server's decode_sample_rate. */
const val ANALYSIS_SAMPLE_RATE = 44100

/** Duration limits, as the server's min_duration_s and max_duration_s. */
data class AudioLimits(val minSeconds: Double = 1.0, val maxSeconds: Double = 900.0)

/** What [streamAudio] decoded: the track's own rate, channels and length, and its length at 44.1 kHz. */
data class StreamInfo(val sourceSampleRate: Int, val sourceChannels: Int, val sourceFrames: Long, val samples: Long) {
    val durationSeconds: Double get() = samples.toDouble() / ANALYSIS_SAMPLE_RATE
}

/** Decodes [source] once, as mono 44.1 kHz audio, and gives every chunk to every sink in turn:
 *  the streaming form of the server's decode() and check_duration() (chordify_backend/app/
 *  audio_io.py), which hold the whole song in memory. Errors carry the server's codes. */
fun streamAudio(
    source: AudioSource,
    sinks: List<SampleSink>,
    limits: AudioLimits = AudioLimits(),
    isCancelled: () -> Boolean = { false },
): StreamInfo {
    var resampler: Resampler? = null
    var samples = 0L
    fun emit(chunk: FloatArray) {
        if (chunk.isEmpty()) return
        samples += chunk.size
        if (samples > limits.maxSeconds * ANALYSIS_SAMPLE_RATE) {
            throw EngineException(ErrorCode.AUDIO_TOO_LONG,
                String.format(Locale.ROOT, "The audio is longer than the %.0f min limit.", limits.maxSeconds / 60))
        }
        for (sink in sinks) sink.write(chunk, chunk.size)
    }
    try {
        val track = AudioDecoder(source).decode(
            isCancelled,
            onStart = { rate -> resampler = Resampler(rate, ANALYSIS_SAMPLE_RATE) },
            onSamples = { chunk -> emit(resampler!!.process(chunk)) },
        )
        emit(resampler!!.finish())
        if (samples == 0L) throw EngineException(ErrorCode.DECODE_FAILED, "The file contains no audio samples.")
        val seconds = samples.toDouble() / ANALYSIS_SAMPLE_RATE
        if (seconds < limits.minSeconds) {
            throw EngineException(ErrorCode.AUDIO_TOO_SHORT,
                String.format(Locale.ROOT, "The audio is %.2f s long; at least %.0f s is needed.", seconds, limits.minSeconds))
        }
        return StreamInfo(track.sampleRate, track.channels, track.frames, samples)
    } finally {
        resampler?.close()
    }
}
