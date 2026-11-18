package com.arbaba.chordifymobile.engine.analysis

import com.arbaba.chordifymobile.engine.acoustic.ChordBundle
import com.arbaba.chordifymobile.engine.acoustic.sha256
import com.arbaba.chordifymobile.engine.lm.NgramProgressionModel
import com.arbaba.chordifymobile.engine.vocab.VOCABULARIES
import com.arbaba.chordifymobile.engine.vocab.Vocabulary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What an analysis decodes with, as chordify_backend/app/pipeline/models.py's Models: the chord
 *  model's bundle (its vocabulary, decoder settings and class prior) and the progression model.
 *  The ONNX session itself lives in ChordModel, so everything here also runs on the JVM. */
class Models(val bundle: ChordBundle, val lm: NgramProgressionModel, val lmId: String) {
    val vocabulary: Vocabulary = VOCABULARIES.getValue(bundle.vocabulary)

    /** The result's "models" block, in the server's order. */
    val ids: Map<String, String> = modelIds(bundle, lmId)

    /** Models.fingerprint: part of an analysis's cache key, so that a new model or feature
     *  revision means a new analysis, never a stale result. */
    val fingerprint: String get() = modelFingerprint(bundle, lmId)

    companion object {
        /** chordify_backend.app.pipeline.beats.TRACKER_ID */
        const val BEATS_ID = "librosa-beat-track"
        const val LM_BUNDLE = "models/progression-ngram/1.0.0"

        /** The id and model file name of a progression model's bundle.json. */
        fun readLmBundle(text: String): Pair<String, String> {
            val json = Json.parseToJsonElement(text).jsonObject
            return json.getValue("id").jsonPrimitive.content to
                json.getValue("files").jsonObject.getValue("model").jsonPrimitive.content
        }
    }
}

/** The "models" block of a result made with [bundle] and the progression model [lmId]. */
fun modelIds(bundle: ChordBundle, lmId: String): Map<String, String> = linkedMapOf(
    "chord" to bundle.id, "lm" to lmId, "beats" to Models.BEATS_ID, "features" to bundle.featureSpec.kind,
    "vocabulary" to VOCABULARIES.getValue(bundle.vocabulary).name,
)

/** The server's Models.fingerprint without loading either model: the first 16 hex digits of the
 *  SHA-256 of json.dumps(ids | {"feature_revision": revision}, sort_keys=True). */
fun modelFingerprint(bundle: ChordBundle, lmId: String): String {
    val fields = modelIds(bundle, lmId).mapValues { JsonPrimitive(it.value).toString() } +
        ("feature_revision" to bundle.featureSpec.revision.toString())
    val text = fields.toSortedMap().entries.joinToString(", ", "{", "}") { (name, value) -> "${JsonPrimitive(name)}: $value" }
    return sha256(text.toByteArray(Charsets.UTF_8)).take(16)
}
