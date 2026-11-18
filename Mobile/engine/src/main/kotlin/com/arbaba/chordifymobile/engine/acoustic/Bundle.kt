package com.arbaba.chordifymobile.engine.acoustic

import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import com.arbaba.chordifymobile.engine.features.FeatureSpec
import com.arbaba.chordifymobile.engine.features.NNLS_BOTHCHROMA
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/** A model bundle's bundle.json (chordify_ai/export/bundle.py), as chordify_core.acoustic.OnnxChordModel reads it. */
class ChordBundle(
    val id: String,
    val modelFile: String,
    val sha256: String,
    val featureSpec: FeatureSpec,
    val vocabulary: String,
    val outputs: List<String>,
    val window: Int,
    val margin: Int,
    /** The numeric settings of the bundle's "decoder" block (alpha, self_prob, subdivide, ...). */
    val decoder: Map<String, Double>,
    val classPrior: DoubleArray?,
) {
    companion object {
        /** Reads bundle.json, refusing a model whose features this engine does not compute. */
        fun parse(text: String): ChordBundle {
            val json = Json.parseToJsonElement(text).jsonObject
            val features = json.getValue("features").jsonObject
            val spec = FeatureSpec(
                kind = features.text("kind"),
                sampleRate = features.getValue("sample_rate").jsonPrimitive.int,
                hop = features.getValue("hop").jsonPrimitive.int,
                nBins = features.getValue("n_bins").jsonPrimitive.int,
                offset = features["offset"]?.jsonPrimitive?.doubleOrNull ?: NNLS_BOTHCHROMA.offset,
                revision = features["revision"]?.jsonPrimitive?.int ?: 1,
            )
            if (spec != NNLS_BOTHCHROMA) {
                throw EngineException(ErrorCode.MODEL_UNAVAILABLE,
                    "The model needs ${spec.kind} features (revision ${spec.revision}); this engine computes " +
                        "${NNLS_BOTHCHROMA.kind} revision ${NNLS_BOTHCHROMA.revision}.")
            }
            val files = json.getValue("files").jsonObject
            val inference = json["inference"]?.jsonObject
            return ChordBundle(
                id = json.text("id"),
                modelFile = files.text("model"),
                sha256 = files.text("sha256"),
                featureSpec = spec,
                vocabulary = json.text("vocabulary"),
                outputs = json.getValue("outputs").jsonArray.map { it.jsonPrimitive.content },
                window = inference?.get("window")?.jsonPrimitive?.int ?: 512,
                margin = inference?.get("margin")?.jsonPrimitive?.int ?: 64,
                decoder = json["decoder"]?.jsonObject?.mapNotNull { (key, value) ->
                    value.jsonPrimitive.doubleOrNull?.let { key to it }
                }?.toMap().orEmpty(),
                classPrior = json["class_prior"]?.jsonArray?.map { it.jsonPrimitive.content.toDouble() }?.toDoubleArray(),
            )
        }

        private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
    }
}

/** Lower-case hex SHA-256, as hashlib.sha256(...).hexdigest(). */
fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
