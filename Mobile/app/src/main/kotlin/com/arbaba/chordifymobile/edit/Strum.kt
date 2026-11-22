package com.arbaba.chordifymobile.edit

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
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

private const val SAMPLE_RATE = 44100
private const val TAG = "Strum"

/** The one strum that may be playing: its track and the thread that owns and releases it. */
private object Playing {
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    @Synchronized fun stopCurrent() {
        thread?.interrupt()
        track?.let { releaseQuietly(it) }
        thread = null
        track = null
    }

    @Synchronized fun begin(owner: Thread) { stopCurrent(); thread = owner }

    @Synchronized fun attach(owner: Thread, created: AudioTrack): Boolean {
        if (thread !== owner) return false
        track = created
        return true
    }

    @Synchronized fun finish(owner: Thread, created: AudioTrack?) {
        created?.let { releaseQuietly(it) }
        if (thread === owner) { thread = null; track = null }
    }
}

private fun releaseQuietly(track: AudioTrack) {
    try { track.stop() } catch (_: IllegalStateException) {}
    try { track.release() } catch (_: IllegalStateException) {}
}

/** Stops the strum that is playing, if any (call when the editor closes). */
fun stopStrum() = Playing.stopCurrent()

/** Plays [shape] as a strum through a static AudioTrack, on a background thread. A new strum
 *  stops the one before it; the track is always released, even when playback fails. */
fun strum(shape: List<Int>, capo: Int) {
    val frequencies = strumFrequencies(shape, capo)
    if (frequencies.isEmpty()) return
    val owner = Thread {
        var track: AudioTrack? = null
        try {
            val pcm = pluck(frequencies, SAMPLE_RATE)
            val built = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track = built
            if (!Playing.attach(Thread.currentThread(), built)) return@Thread
            val written = built.write(pcm, 0, pcm.size)
            if (written != pcm.size) {
                Log.w(TAG, "AudioTrack.write returned $written of ${pcm.size}")
                return@Thread
            }
            built.play()
            Thread.sleep(pcm.size * 1000L / SAMPLE_RATE + 100)
        } catch (_: InterruptedException) {
            // Superseded by a newer strum, or the editor closed.
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not play the strum", e)
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "Could not play the strum", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Could not play the strum", e)
        } finally {
            Playing.finish(Thread.currentThread(), track)
        }
    }
    Playing.begin(owner)
    owner.start()
}
