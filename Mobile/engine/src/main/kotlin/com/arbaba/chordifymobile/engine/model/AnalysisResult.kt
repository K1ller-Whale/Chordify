package com.arbaba.chordifymobile.engine.model

import com.arbaba.chordifymobile.engine.lm.Prediction
import com.arbaba.chordifymobile.engine.lm.PriorEntry
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The analysis document and the next-chord response as the server stores and serves them
// (chordify_backend/app/schemas.py): the same fields, names and defaults, so either side reads
// what the other writes. Lists the server leaves out of a chord (a "no chord" has no
// predictions) default to empty, as pydantic fills them.

@Serializable
data class KeyInfo(val tonic: String, val mode: String, val confidence: Double = 0.0)

@Serializable
data class KeySegment(val tonic: String, val mode: String, val confidence: Double = 0.0, val start: Double, val end: Double)

@Serializable
data class KeySummary(val global: KeyInfo, val segments: List<KeySegment>)

@Serializable
data class Alternative(val label: String, val display: String, val p: Double)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ChordSegment(
    val index: Int,
    val start: Double,
    val end: Double,
    val beats: Int? = null,
    val label: String,
    val display: String,
    val root: String? = null,
    val quality: String? = null,
    val bass: String? = null,
    val roman: String? = null,
    @SerialName("roman_triad") val romanTriad: String? = null,
    val function: String? = null,
    @SerialName("scale_hint") val scaleHint: String? = null,
    val confidence: Double,
    val alternatives: List<Alternative> = emptyList(),
    val next: List<Prediction> = emptyList(),
    @SerialName("theory_only") val theoryOnly: List<PriorEntry> = emptyList(),
    val surprise: Double? = null,
    /** True for a chord the user changed; never written while false, so unedited JSON is unchanged. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val edited: Boolean = false,
)

@Serializable
data class Bar(val start: Double, val end: Double, val chords: List<String>)

@Serializable
data class Section(val letter: String, val label: String, val start: Double, val end: Double)

@Serializable
data class TimeShare(val display: String, val seconds: Double, val share: Double)

@Serializable
data class Pattern(val roman: List<String>, val name: String? = null, val count: Int)

@Serializable
data class Summary(
    @SerialName("unique_chords") val uniqueChords: Int,
    @SerialName("time_share") val timeShare: List<TimeShare>,
    val patterns: List<Pattern>,
    val predictability: Double? = null,
)

@Serializable
data class Tempo(val bpm: Double?, val meter: String, val confidence: Double = 0.0)

@Serializable
data class Source(
    val filename: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val duration: Double,
    val sha256: String? = null,
)

@Serializable
data class AnalysisResult(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("analysis_id") val analysisId: String,
    val status: String = "completed",
    val source: Source,
    val models: Map<String, String>,
    val tempo: Tempo,
    val key: KeySummary,
    val beats: List<Double>,
    val downbeats: List<Double>,
    val bars: List<Bar>,
    val sections: List<Section> = emptyList(),
    val chords: List<ChordSegment>,
    val summary: Summary,
)

@Serializable
data class ProgressionKey(val tonic: String, val mode: String, val confidence: Double = 0.0, val estimated: Boolean)

@Serializable
data class ProgressionResponse(
    @SerialName("key_used") val keyUsed: ProgressionKey,
    val predictions: List<Prediction>,
    @SerialName("theory_only") val theoryOnly: List<PriorEntry>,
    val model: String,
)

/** The server's JSON: every field written, defaults and nulls included, as pydantic's model_dump. */
val ServerJson: Json = Json { encodeDefaults = true }

fun AnalysisResult.toJson(): String = ServerJson.encodeToString(AnalysisResult.serializer(), this)

fun parseAnalysisResult(text: String): AnalysisResult = ServerJson.decodeFromString(AnalysisResult.serializer(), text)
