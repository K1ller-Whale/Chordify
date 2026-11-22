package com.arbaba.chordifymobile.engine.analysis

import com.arbaba.chordifymobile.engine.model.AnalysisResult
import kotlinx.serialization.Serializable

/** One chord of an edited analysis: [label] is a Harte label as it sounds ("E:min", "C:maj/3", "N"). */
@Serializable
data class ChordSpan(val start: Double, val end: Double, val label: String, val edited: Boolean = false)

/** The analysis rebuilt from [chords] without audio (spec §3.2). [chords] must be normalised
 *  (sorted, touching, covering [0, result.source.duration]). Returns exactly one
 *  ChordSegment per span, in order, with index = position. */
fun rebuild(result: AnalysisResult, chords: List<ChordSpan>, models: Models): AnalysisResult = TODO("E1")
