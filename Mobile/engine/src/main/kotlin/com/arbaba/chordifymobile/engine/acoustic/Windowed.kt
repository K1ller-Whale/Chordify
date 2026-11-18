package com.arbaba.chordifymobile.engine.acoustic

import kotlin.math.exp

/** chordify_core.acoustic.windowed: runs a sequence model over [n] frames in overlapping windows
 *  of [window] frames, keeping each window's centre (all but [margin] frames at each inner edge),
 *  so every kept frame has context. [run] gets (first frame, frame count) and returns every
 *  output's rows for those frames. */
fun windowed(n: Int, window: Int, margin: Int, run: (start: Int, length: Int) -> Map<String, Array<FloatArray>>): Map<String, Array<FloatArray>> {
    if (n <= window) return run(0, n)
    val step = window - 2 * margin
    val outputs = LinkedHashMap<String, Array<FloatArray>>()
    var start = 0
    while (true) {
        val s = minOf(start, n - window)
        val chunk = run(s, window)
        val keepFrom = if (s == 0) 0 else margin
        val keepTo = if (s + window >= n) window else window - margin
        for ((name, rows) in chunk) {
            val buffer = outputs.getOrPut(name) { Array(n) { FloatArray(rows[0].size) } }
            for (i in keepFrom until keepTo) buffer[s + i] = rows[i]
        }
        if (s + window >= n) break
        start = s + step
    }
    return outputs
}

/** chordify_core.acoustic.softmax over each row, in float32 as numpy computes it. */
fun softmaxRows(logits: Array<FloatArray>): Array<FloatArray> = Array(logits.size) { t ->
    val row = logits[t]
    val max = row.max()
    val e = FloatArray(row.size) { exp((row[it] - max).toDouble()).toFloat() }
    val sum = e.sum()
    FloatArray(row.size) { e[it] / sum }
}

/** chordify_core.acoustic.sigmoid, in float32. */
fun sigmoid(logits: FloatArray): FloatArray = FloatArray(logits.size) { (1.0 / (1.0 + exp(-logits[it].toDouble()))).toFloat() }
