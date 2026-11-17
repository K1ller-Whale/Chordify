package com.arbaba.chordifymobile.engine.features

import kotlin.math.ln
import kotlin.math.sqrt

// Port of chordify_core/features.py: the NNLS bothchroma feature spec, its bin order and its
// normalisation. Chroma features are stored with pitch class 0 = C; the NNLS plugin starts at A.

/** chordify_core.features.FeatureSpec: what a model's input frames are. */
data class FeatureSpec(
    val kind: String,
    val sampleRate: Int,
    val hop: Int,
    val nBins: Int,
    val offset: Double = 0.0, // time (s) at the centre of frame 0
    val revision: Int = 1, // bumped whenever the extractor's output changes
) {
    val frameRate: Double get() = sampleRate.toDouble() / hop

    /** Centre time of every frame: `offset + i / frameRate`. */
    fun frameTimes(nFrames: Int): DoubleArray = DoubleArray(nFrames) { offset + it / frameRate }
}

/** NNLS bin 0 is A (pitch class 9). */
const val NNLS_ORDER_OFFSET = 9
const val NNLS_BLOCK = 16384

/** Billboard's features: NNLS Chroma at 44.1 kHz, step 2048 (46.4 ms), block 16384. The Vamp host
 *  stamps each frame at the centre of its block, so frame 0 is at 0.186 s. */
val NNLS_BOTHCHROMA = FeatureSpec("nnls_bothchroma", 44100, 2048, 24, offset = NNLS_BLOCK / 2 / 44100.0)

/** Roll each 12-bin half of A-based NNLS output so that bin 0 = C (`numpy.roll` by 9). */
fun nnlsToCOrder(bothchroma: Array<FloatArray>): Array<FloatArray> = Array(bothchroma.size) { t ->
    val row = bothchroma[t]
    require(row.size == 24) { "frame $t has ${row.size} values, expected 24" }
    FloatArray(24) { i -> row[i / 12 * 12 + Math.floorMod(i % 12 - NNLS_ORDER_OFFSET, 12)] }
}

/** (T, 24) raw bothchroma -> (T, 25): each 12-bin half divided by its own max, plus a per-song
 *  z-scored log-energy channel, clipped to ±5, so silence (N) stays distinguishable. */
fun normaliseBothchroma(features: Array<FloatArray>, eps: Float = 1e-6f): Array<FloatArray> {
    val energy = DoubleArray(features.size) { t -> ln(eps.toDouble() + features[t].sumOf { it.toDouble() }) }
    val mean = energy.average()
    val std = sqrt(energy.sumOf { (it - mean) * (it - mean) } / energy.size)
    val spread = if (std > 1e-6) std else 1.0
    return Array(features.size) { t ->
        val row = features[t]
        val bassMax = maxOf((0 until 12).maxOf { row[it] }, eps)
        val trebleMax = maxOf((12 until 24).maxOf { row[it] }, eps)
        FloatArray(25) { i ->
            when {
                i < 12 -> row[i] / bassMax
                i < 24 -> row[i] / trebleMax
                else -> ((energy[t] - mean) / spread).coerceIn(-5.0, 5.0).toFloat()
            }
        }
    }
}
