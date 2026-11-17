package com.arbaba.chordifymobile.engine.util

import java.math.BigDecimal
import java.math.RoundingMode

// The Python and numpy behaviours the port of chordify_core depends on, in one place, so
// every module rounds, searches and splits exactly as the Python code does.

/** Python's `round(x, digits)`: rounded from the exact binary value, ties to even. */
fun pyRound(x: Double, digits: Int): Double =
    if (!x.isFinite()) x else BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()

/** Python's `int(round(x))`: the nearest integer, ties to even. */
fun pyRoundToInt(x: Double): Int = Math.rint(x).toInt()

/** `numpy.argmax(values[:until])`: the first index of the largest value. */
fun argmax(values: DoubleArray, until: Int = values.size): Int {
    var best = 0
    for (i in 1 until until) if (values[i] > values[best]) best = i
    return best
}

/** `numpy.searchsorted(sorted, value, side="right" if right else "left")` on an ascending array. */
fun searchSorted(sorted: DoubleArray, value: Double, right: Boolean = false): Int {
    var lo = 0
    var hi = sorted.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        val after = if (right) sorted[mid] <= value else sorted[mid] < value
        if (after) lo = mid + 1 else hi = mid
    }
    return lo
}

/** `numpy.median`: the middle value, or the mean of the two middle values. */
fun median(values: DoubleArray): Double {
    require(values.isNotEmpty()) { "median of no values" }
    val sorted = values.sortedArray()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

/** Python's `str.partition(sep)`: (before, whether `sep` was found, after), split at the first `sep`. */
fun String.partition3(sep: Char): Triple<String, Boolean, String> {
    val at = indexOf(sep)
    return if (at < 0) Triple(this, false, "") else Triple(substring(0, at), true, substring(at + 1))
}
