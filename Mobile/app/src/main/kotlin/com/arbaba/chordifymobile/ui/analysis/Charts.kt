package com.arbaba.chordifymobile.ui.analysis

import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ChordSegment
import com.arbaba.chordifymobile.music.MAJOR_CIRCLE
import com.arbaba.chordifymobile.music.MINOR_CIRCLE
import com.arbaba.chordifymobile.music.chordIndexAt
import com.arbaba.chordifymobile.music.circleIndex
import com.arbaba.chordifymobile.music.isMinorQuality
import com.arbaba.chordifymobile.music.isNoChord
import com.arbaba.chordifymobile.music.noteToPc
import kotlin.math.log2

// The logic of the timeline and the panels (Timeline.tsx, Panels.tsx, AnalysisPage.tsx), apart
// from their drawing.

/** A change is surprising when the model gave it less than a 15% chance (AnalysisPage.tsx). */
val SURPRISE_BITS: Double = log2(1 / 0.15)

fun surprisingChords(chords: List<ChordSegment>): Set<Int> =
    chords.filter { !isNoChord(it.label) && (it.surprise ?: 0.0) > SURPRISE_BITS }.mapTo(HashSet()) { it.index }

/** The bar playing at [t], or -1 before the first bar. */
fun barIndexAt(barStarts: List<Double>, t: Double): Int =
    if (barStarts.isNotEmpty() && t >= barStarts[0]) chordIndexAt(barStarts, t) else -1

/** Where ← or → (or a swipe) goes: just inside the chord [delta] away from the one at [t]. */
fun chordStep(starts: List<Double>, t: Double, delta: Int): Double =
    starts[(chordIndexAt(starts, t) + delta).coerceIn(0, starts.size - 1)] + 0.01

/** The detail track's playhead, as a share of its width. */
const val PLAYHEAD = 0.33f

/** The time under [x] on the detail track, which shows [time] at its playhead. */
fun detailSeek(time: Double, x: Float, width: Float, pixelsPerSecond: Float): Double =
    time + (x - width * PLAYHEAD) / pixelsPerSecond

/** The time under [x] on the overview, which shows the whole song. */
fun overviewSeek(x: Float, width: Float, duration: Double): Double = (x / width).toDouble() * duration

/** A chord's place on the circle of fifths: its root's pitch class, and whether it is minor. */
fun nodeKey(chord: ChordSegment): String? {
    val root = chord.root ?: return null
    if (isNoChord(chord.label)) return null
    return "${noteToPc(root)}:${if (isMinorQuality(chord.quality)) "m" else "M"}"
}

/** One of the song's chords on the circle: [label] is the circle's fixed name for the place (the
 *  grey name it hides), [text] the chord as the song spells it (Gb in Db major, not F#). */
data class CircleNode(val key: String, val chord: ChordSegment, val share: Double, val index: Int, val minor: Boolean,
                      val label: String, val text: String)

/** The song's chords on the circle, in the order they first sound. */
fun circleNodes(result: AnalysisResult): List<CircleNode> {
    val share = result.summary.timeShare.associate { it.display to it.share }
    val nodes = LinkedHashMap<String, CircleNode>()
    for (chord in result.chords) {
        val key = nodeKey(chord) ?: continue
        if (key in nodes) continue
        val minor = key.endsWith("m")
        val index = circleIndex(key.substringBefore(':').toInt(), minor)
        nodes[key] = CircleNode(key, chord, share[chord.display] ?: 0.0, index, minor,
                                if (minor) MINOR_CIRCLE[index] else MAJOR_CIRCLE[index], "${chord.root}${if (minor) "m" else ""}")
    }
    return nodes.values.toList()
}

/** How often the song moves from one place on the circle to another. */
fun circleTransitions(result: AnalysisResult): Map<Pair<String, String>, Int> {
    val real = result.chords.filter { !isNoChord(it.label) }
    val counts = LinkedHashMap<Pair<String, String>, Int>()
    for (i in 1 until real.size) {
        val a = nodeKey(real[i - 1])
        val b = nodeKey(real[i])
        if (a != null && b != null && a != b) counts.merge(a to b, 1, Int::plus)
    }
    return counts
}

/** What a screen reader says for the circle, as the web's aria-label. */
fun circleDescription(result: AnalysisResult): String =
    "The song's chords on the circle of fifths, key of ${result.key.global.tonic} ${result.key.global.mode}"
