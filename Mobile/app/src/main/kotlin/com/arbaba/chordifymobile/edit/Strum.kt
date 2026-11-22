package com.arbaba.chordifymobile.edit

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

private val OPEN_STRING_HZ = listOf(82.41, 110.0, 146.83, 196.0, 246.94, 329.63)

/** Frequencies of a shape (strings E A D G B e, low to high), counted from [capo]; muted strings are skipped. */
fun strumFrequencies(shape: List<Int>, capo: Int): List<Double> =
    shape.withIndex().filter { it.value >= 0 && it.index < OPEN_STRING_HZ.size }
        .map { OPEN_STRING_HZ[it.index] * 2.0.pow((it.value + capo) / 12.0) }

/** A strum as 16-bit mono PCM: one Karplus-Strong plucked string per frequency, started low to high
 *  [gapSeconds] apart, mixed and normalised so nothing clips. Deterministic for a given [seed]. */
fun pluck(frequencies: List<Double>, sampleRate: Int = 44100, seconds: Double = 1.5,
          gapSeconds: Double = 0.03, seed: Int = 1): ShortArray {
    val total = (seconds * sampleRate).roundToInt()
    val mix = FloatArray(total)
    for ((i, hz) in frequencies.withIndex()) {
        val start = (i * gapSeconds * sampleRate).roundToInt()
        val period = (sampleRate / hz).roundToInt().coerceAtLeast(2)
        val random = Random(seed + i)
        val ring = FloatArray(period) { random.nextFloat() * 2f - 1f }
        var at = 0
        for (n in start until total) {
            val next = ring[(at + 1) % period]
            mix[n] += ring[at]
            ring[at] = 0.996f * 0.5f * (ring[at] + next)
            at = (at + 1) % period
        }
    }
    val peak = mix.maxOfOrNull { abs(it) } ?: 0f
    val scale = if (peak > 0f) 0.9f * Short.MAX_VALUE / peak else 0f
    return ShortArray(total) { (mix[it] * scale).roundToInt().toShort() }
}

/** Plays [shape] as a strum through a static AudioTrack, on a background thread. */
fun strum(shape: List<Int>, capo: Int) {
    val pcm = pluck(strumFrequencies(shape, capo))
    Thread {
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) { t.release() }
                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            track.play()
        } catch (_: Exception) {
            // No audio output: the strum is a nicety, so stay silent.
        }
    }.start()
}
