package com.arbaba.chordifymobile.engine.features

import com.arbaba.chordifymobile.engine.audio.AudioLimits
import com.arbaba.chordifymobile.engine.audio.AudioSource
import com.arbaba.chordifymobile.engine.audio.StreamInfo
import com.arbaba.chordifymobile.engine.audio.streamAudio

/** Raw NNLS bothchroma of a file (C-ordered, one row per frame) and what was decoded. */
class NnlsFeatures(val info: StreamInfo, val raw: Array<FloatArray>)

/** chordify_core.features.nnls_bothchroma on the server's decode of [source], in one streaming pass. */
fun extractNnlsBothchroma(
    source: AudioSource,
    limits: AudioLimits = AudioLimits(),
    isCancelled: () -> Boolean = { false },
): NnlsFeatures = NnlsChroma().use { nnls ->
    val info = streamAudio(source, listOf(nnls), limits, isCancelled)
    NnlsFeatures(info, nnlsToCOrder(nnls.finish()))
}
