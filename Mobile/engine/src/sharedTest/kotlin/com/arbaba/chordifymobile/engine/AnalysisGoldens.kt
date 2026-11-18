package com.arbaba.chordifymobile.engine

import com.arbaba.chordifymobile.engine.acoustic.AcousticOutput
import com.arbaba.chordifymobile.engine.analysis.Measurements
import com.arbaba.chordifymobile.engine.analysis.SourceInfo
import com.arbaba.chordifymobile.engine.beats.BeatInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// The analysis golden file (analysis.json.gz): what the server measured for each input, and
// what its analyse() returned.

/** The beats and chord model output recorded for one input, as the engine's analysis takes them. */
fun JsonObject.measurements(): Measurements {
    val beats = obj("beats")
    val output = obj("output")
    val bpm = beats.getValue("bpm").jsonPrimitive.doubleOrNull
    return Measurements(
        samples = getValue("samples").jsonPrimitive.long,
        beats = BeatInfo(beats.getValue("beats").doubles(), bpm, beats.double("confidence")),
        output = AcousticOutput(chord = output.getValue("chord").floatMatrix(), boundary = output.getValue("boundary").floats(),
                                keyTonic = emptyArray(), keyMode = emptyArray(), root = emptyArray(),
                                bass = output.getValue("bass").floatMatrix()),
    )
}

/** The analysis_id, file name and SHA-256 the server's result was made with. */
fun JsonObject.sourceInfo(analysisId: String): SourceInfo = SourceInfo(analysisId, str("filename"), str("sha256"))
