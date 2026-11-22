package com.arbaba.chordifymobile.engine.analysis

import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.Bar
import com.arbaba.chordifymobile.engine.theory.Key
import com.arbaba.chordifymobile.engine.vocab.LARGE
import com.arbaba.chordifymobile.engine.vocab.noteToPc
import kotlin.math.abs
import kotlinx.serialization.Serializable

/** One chord of an edited analysis: [label] is a Harte label as it sounds ("E:min", "C:maj/3", "N"). */
@Serializable
data class ChordSpan(val start: Double, val end: Double, val label: String, val edited: Boolean = false)

/** The analysis rebuilt from [chords] without audio (spec §3.2). [chords] must be normalised
 *  (sorted, touching, covering [0, result.source.duration]). Returns exactly one
 *  ChordSegment per span, in order, with index = position. */
fun rebuild(result: AnalysisResult, chords: List<ChordSpan>, models: Models): AnalysisResult {
    val global = result.key.global
    val key = Key(noteToPc(global.tonic), global.mode)
    val beatTimes = result.beats.toDoubleArray()
    val kept = result.chords
    val segments = chords.mapIndexedTo(ArrayList()) { i, span ->
        val sameTimes = kept.firstOrNull { abs(it.start - span.start) <= 1e-3 && abs(it.end - span.end) <= 1e-3 }
        val same = sameTimes?.takeIf { !span.edited && it.label == span.label }
        chordSegmentFor(
            i, span.start, span.end, span.label, key, beatTimes,
            quality = if (same != null) same.quality else LARGE.encode(span.label)?.let(LARGE::qualityOf),
            confidence = same?.confidence ?: 1.0,
            alternatives = same?.alternatives ?: emptyList(),
            edited = span.edited,
            // Beats depend on the times only. Rounded times and beats can put a beat on the other
            // side of a 1 ms edge, so a span at the original times keeps the analysis's own count.
            // (A span with new times is counted from rounded values, which can still be one off
            // when a beat sits on that edge.)
            beatCount = sameTimes?.beats,
        )
    }
    val predicted = kept.any { it.next.isNotEmpty() }
    val predictability = if (predicted) withPredictions(models, key, segments, beatTimes.isNotEmpty()) else null
    return result.copy(
        // Bar bounds depend only on downbeats and duration, which stay: keep them, rename the chords.
        bars = result.bars.map { Bar(it.start, it.end, barChords(it.start, it.end, segments)) },
        chords = segments,
        summary = summaryOf(segments, predictability),
    )
}
