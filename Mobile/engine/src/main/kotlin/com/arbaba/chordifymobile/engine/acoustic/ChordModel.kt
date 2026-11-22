package com.arbaba.chordifymobile.engine.acoustic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import com.arbaba.chordifymobile.engine.features.normaliseBothchroma
import java.nio.FloatBuffer

/** chordify_core.acoustic.AcousticOutput: per-frame probabilities from every head of the model. */
class AcousticOutput(
    val chord: Array<FloatArray>,
    val boundary: FloatArray,
    val keyTonic: Array<FloatArray>,
    val keyMode: Array<FloatArray>,
    val root: Array<FloatArray>,
    val bass: Array<FloatArray>,
)

/** An exported ChordNet bundle run with ONNX Runtime on the CPU, as
 *  chordify_core.acoustic.OnnxChordModel runs it on the server. */
class ChordModel internal constructor(val bundle: ChordBundle, model: ByteArray) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        if (sha256(model) != bundle.sha256) {
            throw EngineException(ErrorCode.MODEL_UNAVAILABLE,
                "The chord model ${bundle.id} is damaged (its SHA-256 does not match bundle.json); reinstall the app.")
        }
        session = environment.createSession(model, OrtSession.SessionOptions())
    }

    /** Raw logits of every head for model input [input] (normalised features, one row per frame). */
    fun logits(input: Array<FloatArray>): Map<String, Array<FloatArray>> =
        windowed(input.size, bundle.window, bundle.margin) { start, length -> run(input, start, length) }

    /** OnnxChordModel.predict: raw NNLS features in, probabilities out. */
    fun predict(raw: Array<FloatArray>): AcousticOutput {
        val logits = logits(normaliseBothchroma(raw))
        return AcousticOutput(
            chord = softmaxRows(logits.getValue("chord")),
            boundary = sigmoid(FloatArray(raw.size) { logits.getValue("boundary")[it][0] }),
            keyTonic = softmaxRows(logits.getValue("key_tonic")),
            keyMode = softmaxRows(logits.getValue("key_mode")),
            root = softmaxRows(logits.getValue("root")),
            bass = softmaxRows(logits.getValue("bass")),
        )
    }

    private fun run(input: Array<FloatArray>, start: Int, length: Int): Map<String, Array<FloatArray>> {
        val width = input[0].size
        val flat = FloatArray(length * width)
        for (t in 0 until length) input[start + t].copyInto(flat, t * width)
        val shape = longArrayOf(1, length.toLong(), width.toLong())
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(flat), shape).use { tensor ->
            session.run(mapOf(INPUT to tensor)).use { result ->
                bundle.outputs.associateWith { name ->
                    val value = result.get(name).get() as OnnxTensor
                    val dims = value.info.shape
                    val size = if (dims.size == 3) dims[2].toInt() else 1 // boundary is (1, time)
                    val buffer = value.floatBuffer
                    Array(length) { t -> FloatArray(size) { buffer.get(t * size + it) } }
                }
            }
        }
    }

    override fun close() = session.close()

    companion object {
        const val INPUT = "features"
        const val BUNDLE = "models/chordnet-chroma/3.0.0"

        /** The bundle.json of a bundle the app ships, read without its model. */
        fun bundleFromAssets(context: Context, directory: String = BUNDLE): ChordBundle =
            ChordBundle.parse(context.assets.open("$directory/bundle.json").use { it.readBytes().decodeToString() })

        /** The bundle the app ships (copied from the repository's models/ into the engine's assets); pass [bundle]
         *  when its bundle.json is read already. */
        fun fromAssets(context: Context, directory: String = BUNDLE,
                       bundle: ChordBundle = bundleFromAssets(context, directory)): ChordModel {
            val model = context.assets.open("$directory/${bundle.modelFile}").use { it.readBytes() }
            return ChordModel(bundle, model)
        }
    }
}
