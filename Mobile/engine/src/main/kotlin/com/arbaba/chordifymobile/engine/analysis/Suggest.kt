package com.arbaba.chordifymobile.engine.analysis

import com.arbaba.chordifymobile.engine.lm.predictNext
import com.arbaba.chordifymobile.engine.model.ProgressionKey
import com.arbaba.chordifymobile.engine.model.ProgressionResponse
import com.arbaba.chordifymobile.engine.theory.estimateKeyFromChords
import com.arbaba.chordifymobile.engine.theory.parseKey
import com.arbaba.chordifymobile.engine.util.argmax
import com.arbaba.chordifymobile.engine.util.pyRound
import com.arbaba.chordifymobile.engine.vocab.NO_CHORD
import com.arbaba.chordifymobile.engine.vocab.Vocabulary
import com.arbaba.chordifymobile.engine.vocab.parse
import com.arbaba.chordifymobile.engine.vocab.parseDisplay

/** POST /api/v2/progressions/next (chordify_backend/app/api/progressions.py): next-chord
 *  suggestions for typed chord names, in [key] or the key the chords suggest. Throws
 *  IllegalArgumentException with the server's message where the server answers INVALID_REQUEST. */
fun nextChords(models: Models, chords: List<String>, key: String? = null, k: Int = 3,
               durationsBeats: List<Double>? = null): ProgressionResponse {
    require(chords.size in 1..512) { "Between 1 and 512 chords are needed." }
    require(k in 1..10) { "k must be between 1 and 10." }
    val labels = chords.map { parseDisplay(it) }
    require(durationsBeats == null || durationsBeats.size == labels.size) { "durations_beats must have one entry per chord." }
    val beats = durationsBeats ?: List(labels.size) { 4.0 }
    val (useKey, confidence, estimated) = if (!key.isNullOrEmpty()) {
        Triple(parseKey(key), 1.0, false)
    } else {
        estimateKeyFromChords(labels.zip(beats)).let { (estimate, score) -> Triple(estimate, score, true) }
    }
    val real = labels.zip(beats).filter { parse(it.first).isChord }
    require(real.isNotEmpty()) { "At least one chord (not N.C.) is needed." }
    val (predictions, prior) = predictNext(models.lm, real.map { it.first }, useKey, k = k, historyBeats = real.map { it.second })
    return ProgressionResponse(ProgressionKey(useKey.tonicName, useKey.mode, pyRound(confidence, 4), estimated),
                               predictions, prior, models.lmId)
}

/** single_chord (chordify_backend/app/pipeline/analysis.py): one label for a short clip, from the
 *  chord model's per-frame probabilities. Frames count with weight 1 - P(N); the best chord
 *  class wins, with its share of the chord mass as the confidence. The server computes this in
 *  float32 and the engine in float64, so confidences agree to about 1e-6. */
fun singleChord(chord: Array<FloatArray>, vocabulary: Vocabulary): Pair<String, Double> {
    val n = vocabulary.noChord
    val weights = DoubleArray(chord.size) { 1.0 - chord[it][n] }
    val total = weights.sum()
    if (total < 1e-3) return NO_CHORD to chord.sumOf { it[n].toDouble() } / chord.size
    val mass = DoubleArray(vocabulary.size) { c ->
        var sum = 0.0
        for (t in chord.indices) sum += chord[t][c] * weights[t]
        sum / total
    }
    mass[n] = 0.0
    val best = argmax(mass)
    return vocabulary.decode(best) to mass[best] / mass.sum()
}
