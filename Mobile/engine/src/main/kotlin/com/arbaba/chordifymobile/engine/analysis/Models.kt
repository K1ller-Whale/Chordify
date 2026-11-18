package com.arbaba.chordifymobile.engine.analysis

import com.arbaba.chordifymobile.engine.acoustic.ChordBundle
import com.arbaba.chordifymobile.engine.lm.NgramProgressionModel
import com.arbaba.chordifymobile.engine.vocab.VOCABULARIES
import com.arbaba.chordifymobile.engine.vocab.Vocabulary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What an analysis decodes with, as chordify_backend/app/pipeline/models.py's Models: the chord
 *  model's bundle (its vocabulary, decoder settings and class prior) and the progression model.
 *  The ONNX session itself lives in ChordModel, so everything here also runs on the JVM. */
class Models(val bundle: ChordBundle, val lm: NgramProgressionModel, val lmId: String) {
    val vocabulary: Vocabulary = VOCABULARIES.getValue(bundle.vocabulary)

    /** The result's "models" block, in the server's order. */
    val ids: Map<String, String> = linkedMapOf(
        "chord" to bundle.id, "lm" to lmId, "beats" to BEATS_ID, "features" to bundle.featureSpec.kind,
        "vocabulary" to vocabulary.name,
    )

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
