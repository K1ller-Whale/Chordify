package com.arbaba.chordifymobile.quick

import com.arbaba.chordifymobile.engine.audio.AudioLimits
import com.arbaba.chordifymobile.engine.audio.AudioSource
import com.arbaba.chordifymobile.engine.audio.SampleSink
import com.arbaba.chordifymobile.engine.audio.streamAudio
import com.arbaba.chordifymobile.engine.vocab.NO_CHORD
import com.arbaba.chordifymobile.engine.vocab.displayName
import com.arbaba.chordifymobile.engine.vocab.parse
import com.arbaba.chordifymobile.music.Shape
import com.arbaba.chordifymobile.music.guitarShape

// Quick chord (spec §6.2, chordify-frontend/src/pages/QuickChord.tsx): one chord from a short
// clip, named in full (Am7, not the web's v1 A), with its confidence and guitar shape.

/** The shortest and the longest clip Quick chord takes. */
val CLIP_LIMITS = AudioLimits(minSeconds = 0.5, maxSeconds = 30.0)

/** A recording stops by itself after this long. */
const val RECORD_SECONDS = 3.0

/** Keeps every sample it is given. */
class CollectingSink : SampleSink {
    private var samples = FloatArray(1 shl 16)
    private var size = 0

    override fun write(samples: FloatArray, count: Int) {
        if (size + count > this.samples.size) this.samples = this.samples.copyOf(maxOf(this.samples.size * 2, size + count))
        samples.copyInto(this.samples, size, 0, count)
        size += count
    }

    fun samples(): FloatArray = samples.copyOf(size)
}

/** A clip decoded once, as mono 44.1 kHz audio. */
fun decodeClip(source: AudioSource, limits: AudioLimits = CLIP_LIMITS): FloatArray {
    val sink = CollectingSink()
    streamAudio(source, listOf(sink), limits)
    return sink.samples()
}

/** "Am7" for "A:min7", "No chord" for "N". */
fun quickChordName(label: String): String = if (parse(label).label == NO_CHORD) "No chord" else displayName(label)

/** The guitar shape of a label such as "A:min7" or "C:maj/3"; null for "N". */
fun chordShape(label: String): Shape? {
    val body = label.substringBefore('/')
    if (parse(label).label == NO_CHORD) return null
    return if (':' in body) guitarShape(body.substringBefore(':'), body.substringAfter(':')) else guitarShape(body, "maj")
}
