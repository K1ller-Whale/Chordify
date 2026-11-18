package com.arbaba.chordifymobile.engine

import android.content.Context
import com.arbaba.chordifymobile.engine.acoustic.ChordModel
import com.arbaba.chordifymobile.engine.analysis.AnalysisOptions
import com.arbaba.chordifymobile.engine.analysis.Measurements
import com.arbaba.chordifymobile.engine.analysis.Models
import com.arbaba.chordifymobile.engine.analysis.SourceInfo
import com.arbaba.chordifymobile.engine.analysis.Stage
import com.arbaba.chordifymobile.engine.analysis.analyseMeasured
import com.arbaba.chordifymobile.engine.analysis.nextChords
import com.arbaba.chordifymobile.engine.analysis.runFrontEnd
import com.arbaba.chordifymobile.engine.analysis.singleChord
import com.arbaba.chordifymobile.engine.analysis.targetVocabulary
import com.arbaba.chordifymobile.engine.audio.AudioLimits
import com.arbaba.chordifymobile.engine.audio.AudioSource
import com.arbaba.chordifymobile.engine.features.NnlsChroma
import com.arbaba.chordifymobile.engine.features.nnlsToCOrder
import com.arbaba.chordifymobile.engine.lm.NgramProgressionModel
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ProgressionResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** The engine's public API (spec §4.1), with the chord and progression models loaded once.
 *  Not thread-safe: run one call at a time, off the main thread. */
class ChordifyEngine(private val chordModel: ChordModel, val models: Models) : AutoCloseable {

    /** The whole analysis of an audio file, as the server's analysis job produces it. [onProgress]
     *  gets each stage as it starts, with the share of the work done (0..1); [isCancelled] is
     *  checked while decoding and between stages, and throws CancelledException. */
    fun analyse(
        source: AudioSource,
        info: SourceInfo,
        options: AnalysisOptions = AnalysisOptions(),
        onProgress: (Stage, Double) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
        limits: AudioLimits = AudioLimits(),
    ): AnalysisResult {
        targetVocabulary(models, options) // a finer vocabulary is refused before any decoding, as on the server
        onProgress(Stage.DECODE, 0.0)
        val front = runFrontEnd(source, limits, isCancelled) { onProgress(Stage.DECODE, DECODE_SHARE * it) }
        if (isCancelled()) throw CancelledException()
        onProgress(Stage.ACOUSTIC, DECODE_SHARE)
        val output = chordModel.predict(front.features)
        if (isCancelled()) throw CancelledException()
        val result = analyseMeasured(models, Measurements(front.info.samples, front.beats, output), options, info,
                                     onProgress, isCancelled)
        onProgress(Stage.DONE, 1.0)
        return result
    }

    /** Next-chord suggestions for the Songwriter (POST /api/v2/progressions/next). */
    fun nextChords(chords: List<String>, key: String? = null, k: Int = 3, durationsBeats: List<Double>? = null): ProgressionResponse =
        nextChords(models, chords, key, k, durationsBeats)

    /** Quick chord: the one chord in a short mono 44.1 kHz clip, with its confidence (the server's single_chord). */
    fun quickChord(pcm: FloatArray): Pair<String, Double> {
        require(pcm.isNotEmpty()) { "The clip has no samples." }
        val raw = NnlsChroma().use { nnls ->
            nnls.write(pcm, pcm.size)
            nnlsToCOrder(nnls.finish())
        }
        return singleChord(chordModel.predict(raw).chord, models.vocabulary)
    }

    override fun close() = chordModel.close()

    companion object {
        /** The share of [analyse]'s progress taken by the decoding pass (decode, NNLS and beats). */
        const val DECODE_SHARE = 0.6

        /** The engine with the models the app ships (the engine's assets). */
        fun fromAssets(context: Context): ChordifyEngine {
            val chordModel = ChordModel.fromAssets(context)
            try {
                val (lmId, lmFile) = Models.readLmBundle(
                    context.assets.open("${Models.LM_BUNDLE}/bundle.json").use { it.readBytes().decodeToString() })
                // The Android build un-gzips *.gz assets and drops the suffix (the APK compresses them
                // anyway), so the APK holds model.json where bundle.json names model.json.gz.
                val lm = context.assets.open("${Models.LM_BUNDLE}/${lmFile.removeSuffix(".gz")}").use { stream ->
                    NgramProgressionModel.fromJson(Json.parseToJsonElement(stream.readBytes().decodeToString()).jsonObject)
                }
                return ChordifyEngine(chordModel, Models(chordModel.bundle, lm, lmId))
            } catch (error: Exception) {
                chordModel.close()
                throw error
            }
        }
    }
}
