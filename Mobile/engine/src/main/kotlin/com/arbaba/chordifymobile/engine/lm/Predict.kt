package com.arbaba.chordifymobile.engine.lm

import com.arbaba.chordifymobile.engine.theory.Key
import com.arbaba.chordifymobile.engine.theory.roman
import com.arbaba.chordifymobile.engine.theory.transitionReason
import com.arbaba.chordifymobile.engine.util.median
import com.arbaba.chordifymobile.engine.util.pyRound
import com.arbaba.chordifymobile.engine.vocab.displayName
import com.arbaba.chordifymobile.engine.vocab.parse

// High-level prediction with explanations, from chordify_core/lm.py.

data class Prediction(
    val label: String,
    val display: String,
    val roman: String?,
    val p: Double,
    val reason: String,
    val cacheCount: Int = 0,
    val expectedBeats: Double? = null,
)

data class PriorEntry(val roman: String?, val p: Double)

/** Top-[k] next chords after [history] (absolute Harte labels, chord changes in order).
 *
 *  Returns (predictions, corpus-only prior) so a UI can show how much the song's own history
 *  changed the answer. When a predicted chord already occurred in the song, its full label (Bm7
 *  rather than Bm) is used, and `expectedBeats` is its median length so far when [historyBeats]
 *  is given. As in Python, [historyBeats] is indexed like the history with N and X removed. */
fun predictNext(model: NgramProgressionModel, history: List<String>, key: Key, k: Int = 3,
                historyBeats: List<Double>? = null): Pair<List<Prediction>, List<PriorEntry>> {
    val real = history.filter { parse(it).isChord }
    val tokens = real.map { token(it, key)!! }
    val seen = LinkedHashMap<String, String>()
    val lengths = HashMap<String, MutableList<Double>>()
    for ((i, label) in real.withIndex()) {
        seen.getOrPut(tokens[i]) { label }
        if (historyBeats != null && historyBeats[i] > 0) lengths.getOrPut(tokens[i]) { ArrayList() }.add(historyBeats[i])
    }
    val last = real.lastOrNull()
    val predictions = model.top(tokens, k).map { candidate ->
        val label = seen[candidate.token] ?: tokenLabel(candidate.token, key)
        val reasons = ArrayList<String>()
        if (candidate.cacheCount > 0) reasons.add("followed this context ${candidate.cacheCount}× earlier in the song")
        if (last != null) transitionReason(last, label, key)?.let { reasons.add(it) }
        if (reasons.isEmpty()) reasons.add("common continuation in the training corpus")
        val beats = lengths[candidate.token]?.let { median(it.toDoubleArray()) }
        Prediction(label, displayName(label, flats = key.flats), roman(label, key), pyRound(candidate.p, 4),
                   reasons.joinToString("; "), candidate.cacheCount, beats)
    }
    val prior = model.corpusDistribution(tokens).entries.sortedByDescending { it.value }.take(k)
        .map { (t, p) -> PriorEntry(roman(tokenLabel(t, key), key), pyRound(p, 4)) }
    return predictions to prior
}
