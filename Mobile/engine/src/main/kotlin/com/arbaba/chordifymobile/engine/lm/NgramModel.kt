package com.arbaba.chordifymobile.engine.lm

import com.arbaba.chordifymobile.engine.theory.Key
import com.arbaba.chordifymobile.engine.vocab.NO_CHORD
import com.arbaba.chordifymobile.engine.vocab.Vocabulary
import com.arbaba.chordifymobile.engine.vocab.parse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.util.zip.GZIPInputStream

// Port of chordify_core/lm.py: what chord comes next? A key-relative n-gram trained on many
// songs, mixed with a song cache that looks for the current context earlier in the same song.
// Tokens are key-relative: "<semitones above the tonic>:<maj|min>", e.g. V = "7:maj",
// vi = "9:min". The same model supplies the decoder's chord-change matrix.

val TOKENS: List<String> = listOf("maj", "min").flatMap { quality -> (0 until 12).map { "$it:$quality" } }

/** Key-relative token of a chord label; null for N and X. Sus, power and aug count as major,
 *  dim as minor (the third decides). */
fun token(label: String, key: Key): String? {
    val chord = parse(label)
    val root = chord.root ?: return null
    val quality = if (chord.third == "min") "min" else "maj"
    return "${Math.floorMod(root - key.tonic, 12)}:$quality"
}

/** Absolute Harte label for a token in a key: '9:min' in D -> 'B:min'. */
fun tokenLabel(tok: String, key: Key): String {
    val (degree, quality) = tok.split(":")
    return "${key.spell(Math.floorMod(key.tonic + degree.toInt(), 12))}:$quality"
}

fun <T> dedupe(seq: List<T>): List<T> = seq.filterIndexed { i, x -> i == 0 || seq[i - 1] != x }

data class Candidate(val token: String, val p: Double, val cacheCount: Int)

class NgramProgressionModel(
    val order: Int,
    val minCount: Int,
    val cacheWeight: Double,
    val maxContext: Int,
    val counts: List<Map<List<String>, Map<String, Int>>>,
) {
    companion object {
        /** Read `model.json.gz` as written by Python's `NgramProgressionModel.save`. */
        fun load(gzipped: InputStream): NgramProgressionModel =
            GZIPInputStream(gzipped).bufferedReader(Charsets.UTF_8).use {
                fromJson(Json.parseToJsonElement(it.readText()).jsonObject)
            }

        fun fromJson(data: JsonObject): NgramProgressionModel {
            val counts = data.getValue("counts").jsonArray.map { level ->
                level.jsonObject.entries.associate { (context, followers) ->
                    val tokens = if (context.isEmpty()) emptyList() else context.split(' ')
                    tokens to followers.jsonObject.entries.associate { (tok, n) -> tok to n.jsonPrimitive.int }
                }
            }
            return NgramProgressionModel(data.getValue("order").jsonPrimitive.int,
                                         data.getValue("min_count").jsonPrimitive.int,
                                         data.getValue("cache_weight").jsonPrimitive.double,
                                         data.getValue("max_context").jsonPrimitive.int, counts)
        }
    }

    /** Longest context seen at least [minCount] times, smoothed over all tokens. */
    fun corpusDistribution(history: List<String>): Map<String, Double> {
        val h = dedupe(history)
        var chosen: Map<String, Int>? = null
        for (k in minOf(order - 1, h.size) downTo 0) {
            val context = if (k > 0) h.subList(h.size - k, h.size).toList() else emptyList()
            val counts = this.counts[k][context]
            if (counts != null && counts.values.sum() >= minCount) {
                chosen = counts
                break
            }
        }
        val dist = LinkedHashMap<String, Double>()
        for (t in TOKENS) dist[t] = 1e-3
        if (chosen != null) {
            val total = chosen.values.sum().toDouble()
            for ((t, c) in chosen) dist[t] = (dist[t] ?: 1e-3) + c / total
        }
        if (h.isNotEmpty()) dist[h.last()] = 0.0 // a "change" is never to the same chord
        val norm = dist.values.sum()
        for (t in dist.keys) dist[t] = dist.getValue(t) / norm
        return dist
    }

    /** What followed the longest matching context earlier in this song, and that context's length. */
    fun cacheCounts(history: List<String>): Pair<Map<String, Int>, Int> {
        val h = dedupe(history)
        val n = h.size
        for (k in minOf(maxContext, n - 1) downTo 1) {
            val context = h.subList(n - k, n)
            val followers = LinkedHashMap<String, Int>()
            for (j in k until n) {
                if (h.subList(j - k, j) == context) followers[h[j]] = (followers[h[j]] ?: 0) + 1
            }
            followers.remove(h.last())
            if (followers.isNotEmpty()) return followers to k
        }
        return LinkedHashMap<String, Int>() to 0
    }

    fun distribution(history: List<String>): Pair<Map<String, Double>, Map<String, Int>> {
        val corpus = corpusDistribution(history)
        val (cache, _) = cacheCounts(history)
        if (cache.isEmpty()) return corpus to cache
        val total = cache.values.sum().toDouble()
        val mixed = LinkedHashMap<String, Double>()
        for ((t, p) in corpus) mixed[t] = (1 - cacheWeight) * p
        for ((t, c) in cache) mixed[t] = (mixed[t] ?: 0.0) + cacheWeight * c / total
        return mixed to cache
    }

    fun top(history: List<String>, k: Int = 3): List<Candidate> {
        val (dist, cache) = distribution(history)
        return dist.entries.sortedByDescending { it.value }.take(k).map { (t, p) -> Candidate(t, p, cache[t] ?: 0) }
    }

    /** (V, V) chord-change probabilities for the decoder: bigram corpus statistics in [key], shared
     *  equally among classes with the same token; zero diagonal. Tokens are key-relative root +
     *  major/minor family, so C -> C7 is no change for the n-gram; in larger vocabularies those
     *  same-token changes get [sameTokenShare] of each row. */
    fun changeMatrix(vocabulary: Vocabulary, key: Key, sameTokenShare: Double = 0.05): Array<DoubleArray> {
        val labels = vocabulary.labels
        val tokens = labels.map { if (it != NO_CHORD) token(it, key) else null }
        val perToken = tokens.filterNotNull().groupingBy { it }.eachCount()
        val size = labels.size
        val matrix = Array(size) { DoubleArray(size) }
        for (i in 0 until size) {
            val ti = tokens[i]
            val row = matrix[i]
            if (ti == null) {
                row.fill(1.0)
            } else {
                val dist = corpusDistribution(listOf(ti))
                for (j in 0 until size) {
                    val tj = tokens[j]
                    row[j] = if (tj == null) 0.02 else (dist[tj] ?: 1e-3) / perToken.getValue(tj)
                }
            }
            row[i] = 0.0
            val sum = row.sum()
            for (j in row.indices) row[j] /= sum
            if (ti == null) continue
            val same = (0 until size).filter { tokens[it] == ti && it != i }
            if (same.isEmpty()) continue
            val sameSet = same.toSet()
            val others = (0 until size).filter { it != i && it !in sameSet }
            val scale = (1 - sameTokenShare) / others.sumOf { row[it] }
            for (j in others) row[j] *= scale
            for (j in same) row[j] = sameTokenShare / same.size
        }
        return matrix
    }
}
