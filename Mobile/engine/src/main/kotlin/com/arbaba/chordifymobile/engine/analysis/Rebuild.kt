package com.arbaba.chordifymobile.engine.analysis

import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ChordSegment
import com.arbaba.chordifymobile.engine.theory.Key
import com.arbaba.chordifymobile.engine.vocab.LARGE
import com.arbaba.chordifymobile.engine.vocab.noteToPc
import kotlin.math.abs
import kotlinx.serialization.Serializable

/** One chord of an edited analysis: [label] is a Harte label as it sounds ("E:min", "C:maj/3", "N"). */
@Serializable
data class ChordSpan(val start: Double, val end: Double, val label: String, val edited: Boolean = false)

// The stored downbeats and duration are rounded to 3 places, so a final bar the analysis kept at
// 1.0 ms can measure 0.9999999 ms here; without this slack rebuilding would drop it.
private const val REBUILD_MIN_BAR = 1e-3 - 1e-9

/** The analysis rebuilt from [chords] without audio (spec §3.2). [chords] must be normalised
 *  (sorted, touching, covering [0, result.source.duration]). Returns exactly one
 *  ChordSegment per span, in order, with index = position. */
fun rebuild(result: AnalysisResult, chords: List<ChordSpan>, models: Models): AnalysisResult {
    val global = result.key.global
    val key = Key(noteToPc(global.tonic), global.mode)
    val beatTimes = result.beats.toDoubleArray()
    val kept = result.chords
    val segments = chords.mapIndexedTo(ArrayList()) { i, span ->
        val same = if (span.edited) null else kept.firstOrNull {
            it.label == span.label && abs(it.start - span.start) <= 1e-3 && abs(it.end - span.end) <= 1e-3
        }
        chordSegmentFor(
            i, span.start, span.end, span.label, key, beatTimes,
            quality = if (same != null) same.quality else LARGE.encode(span.label)?.let(LARGE::qualityOf),
            confidence = same?.confidence ?: 1.0,
            alternatives = same?.alternatives ?: emptyList(),
            edited = span.edited,
            // Rounded times and beats can land a beat on the other side of a 1 ms edge, so an
            // unchanged chord keeps the count the analysis made from the unrounded ones.
            beatCount = same?.beats,
        )
    }
    val predicted = kept.any { it.next.isNotEmpty() }
    val predictability = if (predicted) withPredictions(models, key, segments, beatTimes.isNotEmpty()) else null
    val downbeats = result.downbeats.toDoubleArray()
    return result.copy(
        bars = if (downbeats.isNotEmpty()) bars(downbeats, result.source.duration, segments, minLength = REBUILD_MIN_BAR) else emptyList(),
        chords = segments,
        summary = summaryOf(segments, predictability),
    )
}
