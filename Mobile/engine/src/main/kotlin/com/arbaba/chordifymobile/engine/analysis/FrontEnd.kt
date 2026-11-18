package com.arbaba.chordifymobile.engine.analysis

import com.arbaba.chordifymobile.engine.audio.AudioLimits
import com.arbaba.chordifymobile.engine.audio.AudioSource
import com.arbaba.chordifymobile.engine.audio.StreamInfo
import com.arbaba.chordifymobile.engine.audio.streamAudio
import com.arbaba.chordifymobile.engine.beats.BeatInfo
import com.arbaba.chordifymobile.engine.beats.BeatTracker
import com.arbaba.chordifymobile.engine.features.NnlsChroma
import com.arbaba.chordifymobile.engine.features.nnlsToCOrder

/** Everything the analysis takes from the audio, from one decoding pass (spec §5): the stream's
 *  facts, raw NNLS bothchroma (C first, one row per frame), the onset envelope and the beats. */
class FrontEnd(val info: StreamInfo, val features: Array<FloatArray>, val onset: FloatArray, val beats: BeatInfo)

/** Decodes [source] once and feeds NNLS Chroma and the beat tracker together, as the server's
 *  analyse() computes features.extract and track_beats from the same samples. */
fun runFrontEnd(source: AudioSource, limits: AudioLimits = AudioLimits(), isCancelled: () -> Boolean = { false }): FrontEnd =
    NnlsChroma().use { nnls ->
        BeatTracker().use { tracker ->
            val info = streamAudio(source, listOf(nnls, tracker), limits, isCancelled)
            val (onset, beats) = tracker.finish()
            FrontEnd(info, nnlsToCOrder(nnls.finish()), onset, beats)
        }
    }
