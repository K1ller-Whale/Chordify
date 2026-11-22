package com.arbaba.chordifymobile.engine.analysis

import com.arbaba.chordifymobile.engine.CancelledException
import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import com.arbaba.chordifymobile.engine.acoustic.AcousticOutput
import com.arbaba.chordifymobile.engine.audio.ANALYSIS_SAMPLE_RATE
import com.arbaba.chordifymobile.engine.beats.BeatInfo
import com.arbaba.chordifymobile.engine.beats.downbeatPhase
import com.arbaba.chordifymobile.engine.decode.Segment
import com.arbaba.chordifymobile.engine.decode.decode
import com.arbaba.chordifymobile.engine.decode.inversion
import com.arbaba.chordifymobile.engine.decode.meanOver
import com.arbaba.chordifymobile.engine.decode.uniformChangeMatrix
import com.arbaba.chordifymobile.engine.lm.predictNext
import com.arbaba.chordifymobile.engine.lm.token
import com.arbaba.chordifymobile.engine.model.Alternative
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.Bar
import com.arbaba.chordifymobile.engine.model.ChordSegment
import com.arbaba.chordifymobile.engine.model.KeyInfo
import com.arbaba.chordifymobile.engine.model.KeySegment
import com.arbaba.chordifymobile.engine.model.KeySummary
import com.arbaba.chordifymobile.engine.model.Pattern
import com.arbaba.chordifymobile.engine.model.Source
import com.arbaba.chordifymobile.engine.model.Summary
import com.arbaba.chordifymobile.engine.model.Tempo
import com.arbaba.chordifymobile.engine.model.TimeShare
import com.arbaba.chordifymobile.engine.theory.Key
import com.arbaba.chordifymobile.engine.theory.describe
import com.arbaba.chordifymobile.engine.theory.estimateKeyFromChords
import com.arbaba.chordifymobile.engine.theory.findPatterns
import com.arbaba.chordifymobile.engine.util.pyRound
import com.arbaba.chordifymobile.engine.vocab.NO_CHORD
import com.arbaba.chordifymobile.engine.vocab.TIERS
import com.arbaba.chordifymobile.engine.vocab.VOCABULARIES
import com.arbaba.chordifymobile.engine.vocab.Vocabulary
import com.arbaba.chordifymobile.engine.vocab.displayName
import com.arbaba.chordifymobile.engine.vocab.parse
import com.arbaba.chordifymobile.engine.vocab.simplify
import com.arbaba.chordifymobile.engine.vocab.transpose
import kotlin.math.log2

// Port of chordify_backend/app/pipeline/analysis.py from the point where beats and the chord
// model's output are known: two decodings, the key, chord names, theory, predictions, downbeats,
// bars and the summary. The audio stages before it are ChordifyEngine.analyse's.

/** The progression model's share of the second decoding's change prior (LM_PRIOR_WEIGHT). */
const val LM_PRIOR_WEIGHT = 0.5

/** The share of no-chord time above which an analysis fails (the server's no_music_threshold). */
const val NO_MUSIC_THRESHOLD = 0.9

/** The server's AnalysisOptions (chordify_backend/app/schemas.py). */
data class AnalysisOptions(val vocabulary: String? = null, val predictions: Boolean = true, val minSegmentBeats: Int = 1) {
    init {
        require(minSegmentBeats in 1..8) { "min_segment_beats must be between 1 and 8, not $minSegmentBeats" }
    }
}

/** What the result says about its audio: the server's analysis_id, and the file's name and SHA-256. */
data class SourceInfo(val analysisId: String, val filename: String? = null, val sha256: String? = null)

/** Progress stages, one list for the engine and the app (spec §5). [id] is the web app's name. */
enum class Stage(val id: String) {
    DECODE("decode"), ACOUSTIC("acoustic"), KEY("key"), DECODE_CHORDS("decode_chords"), ANALYSE("analyse"), DONE("done"),
}

/** What the audio stages measured: the length at 44.1 kHz, the beats and the chord model's output. */
class Measurements(val samples: Long, val beats: BeatInfo, val output: AcousticOutput) {
    val duration: Double get() = samples.toDouble() / ANALYSIS_SAMPLE_RATE
}

/** The chord types a result reports: the model's own, or the coarser tier [AnalysisOptions.vocabulary]
 *  asks for. A finer tier than the model knows is refused, as the server refuses it. */
fun targetVocabulary(models: Models, options: AnalysisOptions): Vocabulary {
    val name = options.vocabulary ?: return models.vocabulary
    val target = VOCABULARIES[name] ?: throw IllegalArgumentException("unknown vocabulary '$name'")
    require(TIERS.indexOf(target.name) <= TIERS.indexOf(models.vocabulary.name)) {
        "the active chord model knows the '${models.vocabulary.name}' chord types, not '${target.name}'"
    }
    return target
}

/** decode_chords: beat-synchronous decoding with the bundle's settings; with [key], the
 *  progression model supplies the change prior, blended with uniform. */
fun decodeChords(
    models: Models,
    posteriors: Array<DoubleArray>,
    boundary: DoubleArray,
    duration: Double,
    beats: BeatInfo,
    key: Key? = null,
    minUnits: Int = 1,
): List<Segment> {
    val spec = models.bundle.featureSpec
    val settings = models.bundle.decoder
    val matrix = key?.let {
        val uniform = uniformChangeMatrix(models.vocabulary.size)
        val progression = models.lm.changeMatrix(models.vocabulary, it)
        Array(uniform.size) { i ->
            DoubleArray(uniform.size) { j -> (1 - LM_PRIOR_WEIGHT) * uniform[i][j] + LM_PRIOR_WEIGHT * progression[i][j] }
        }
    }
    return decode(
        posteriors, spec.frameRate, beats = if (beats.usable) beats.beats else null, changeProb = boundary,
        prior = models.bundle.classPrior, alpha = settings["alpha"] ?: 0.5, selfProb = settings["self_prob"],
        changeMatrix = matrix, subdivide = settings["subdivide"]?.toInt() ?: 1, minUnits = minUnits,
        boundaryWeight = settings["boundary_weight"] ?: 0.5, frameOffset = spec.offset, duration = duration,
    )
}

/** The chord model's probabilities in float64, as decode() converts them. */
fun toDouble(rows: Array<FloatArray>): Array<DoubleArray> =
    Array(rows.size) { t -> DoubleArray(rows[t].size) { rows[t][it].toDouble() } }

/** The first decoding and the key estimated from it; fails with NO_MUSIC_DETECTED when almost
 *  all of the audio decodes as "no chord". */
fun firstPass(models: Models, measured: Measurements): Pair<List<Segment>, Pair<Key, Double>> {
    val duration = measured.duration
    val first = decodeChords(models, toDouble(measured.output.chord), measured.output.boundary.toDouble(), duration, measured.beats)
    val labels = first.map { models.vocabulary.decode(it.index) to it.duration }
    val noChord = labels.filter { it.first == NO_CHORD }.sumOf { it.second }
    if (duration > 0 && noChord / duration > NO_MUSIC_THRESHOLD) {
        throw EngineException(ErrorCode.NO_MUSIC_DETECTED, "${percent(noChord / duration)} of the audio has no recognisable chord.")
    }
    return first to estimateKeyFromChords(labels)
}

/** The server's analyse() once [measured] is known: the whole analysis document. */
fun analyseMeasured(
    models: Models,
    measured: Measurements,
    options: AnalysisOptions,
    source: SourceInfo,
    onProgress: (Stage, Double) -> Unit = { _, _ -> },
    isCancelled: () -> Boolean = { false },
): AnalysisResult {
    val target = targetVocabulary(models, options)
    val vocabulary = models.vocabulary
    val duration = measured.duration
    val beats = measured.beats
    val output = measured.output
    val posteriors = toDouble(output.chord)

    onProgress(Stage.KEY, 0.7)
    val (_, estimate) = firstPass(models, measured)
    val (key, keyConfidence) = estimate
    checkCancelled(isCancelled)
    onProgress(Stage.DECODE_CHORDS, 0.8)
    val segments = decodeChords(models, posteriors, output.boundary.toDouble(), duration, beats, key, options.minSegmentBeats)
    checkCancelled(isCancelled)
    onProgress(Stage.ANALYSE, 0.9)

    val beatTimes = beats.beats
    val threshold = models.bundle.decoder["inversion_threshold"]
    val bass = if (threshold != null) toDouble(output.bass) else null
    val frameTimes = models.bundle.featureSpec.frameTimes(output.chord.size)

    fun name(index: Int, segment: Segment? = null): String {
        var label = vocabulary.decode(index)
        if (bass != null && segment != null) {
            label = inversion(label, meanOver(bass, frameTimes, segment.start, segment.end), threshold!!)
        }
        if (target !== vocabulary) label = simplify(label, target)
        return if (parse(label).isChord) transpose(label, 0, flats = key.flats) else label
    }

    val merged = ArrayList<Merged>() // a coarser vocabulary can make neighbours equal (Cmaj7, C6 -> C, C)
    for (segment in segments) {
        val label = name(segment.index, segment)
        val last = merged.lastOrNull()
        if (last != null && last.label == label) {
            val total = (last.end - last.start) + segment.duration
            last.confidence = ((last.end - last.start) * last.confidence + segment.duration * segment.confidence) / total
            last.end = segment.end
        } else {
            merged += Merged(segment.start, segment.end, label, segment.confidence,
                             segment.alternatives.map { (j, p) -> name(j) to p })
        }
    }

    val chords = merged.mapIndexedTo(ArrayList()) { i, segment -> chordEntry(i, segment, key, target, beatTimes) }
    val predictability = if (options.predictions) withPredictions(models, key, chords, beatTimes.isNotEmpty()) else null

    var downbeats = DoubleArray(0)
    if (beatTimes.isNotEmpty()) {
        val phase = downbeatPhase(beatTimes, chords.drop(1).map { it.start })
        downbeats = beatTimes.filterIndexed { i, _ -> i >= phase && (i - phase) % 4 == 0 }.toDoubleArray()
    }

    val keyInfo = KeyInfo(key.tonicName, key.mode, pyRound(keyConfidence, 4))
    return AnalysisResult(
        analysisId = source.analysisId,
        source = Source(filename = source.filename, duration = pyRound(duration, 3), sha256 = source.sha256),
        models = models.ids,
        tempo = Tempo(beats.bpm, "4/4", beats.confidence),
        key = KeySummary(keyInfo, listOf(KeySegment(keyInfo.tonic, keyInfo.mode, keyInfo.confidence, 0.0, pyRound(duration, 3)))),
        beats = beatTimes.map { pyRound(it, 3) },
        downbeats = downbeats.map { pyRound(it, 3) },
        bars = if (downbeats.isNotEmpty()) bars(downbeats, duration, chords) else emptyList(),
        chords = chords,
        summary = summaryOf(chords, predictability),
    )
}

private class Merged(var start: Double, var end: Double, val label: String, var confidence: Double,
                     val alternatives: List<Pair<String, Double>>)

private fun chordEntry(index: Int, segment: Merged, key: Key, target: Vocabulary, beatTimes: DoubleArray): ChordSegment {
    val label = segment.label
    return chordSegmentFor(
        index, segment.start, segment.end, label, key, beatTimes,
        quality = if (parse(label).isChord) target.qualityOf(target.encode(label)!!) else null,
        confidence = segment.confidence,
        alternatives = segment.alternatives.filter { it.first != label }
            .map { (alt, p) -> Alternative(alt, displayName(alt, key.flats), pyRound(p, 4)) },
    )
}

/** One chord of the result: the names, the theory and the beat count for [label] over [start]..[end]
 *  (rounded to 3 places in the entry, as the server does). [beatCount] replaces the count of beats
 *  inside the span when it is already known. Shared by the analysis and [rebuild]. */
internal fun chordSegmentFor(
    index: Int, start: Double, end: Double, label: String, key: Key, beatTimes: DoubleArray,
    quality: String?, confidence: Double, alternatives: List<Alternative>, edited: Boolean = false,
    beatCount: Int? = null,
): ChordSegment {
    val chord = parse(label)
    val described = if (chord.isChord) describe(label, key) else null
    return ChordSegment(
        index = index,
        start = pyRound(start, 3),
        end = pyRound(end, 3),
        beats = if (beatTimes.isEmpty()) null else beatCount ?: beatTimes.count { it >= start - 1e-3 && it < end - 1e-3 },
        label = label,
        display = displayName(label, flats = key.flats),
        root = chord.root?.let { key.spell(it) },
        quality = quality,
        bass = if (chord.isChord) key.spell(chord.bass!!) else null,
        roman = described?.roman,
        romanTriad = described?.romanTriad,
        function = described?.function,
        scaleHint = described?.scaleHint,
        confidence = pyRound(confidence, 4),
        alternatives = alternatives,
        edited = edited,
    )
}

/** The next-chord predictions, theory priors and surprise of every real chord, written into
 *  [chords] in place. Returns the summary's predictability (null when nothing changed key-wise). */
internal fun withPredictions(models: Models, key: Key, chords: MutableList<ChordSegment>, hasBeats: Boolean): Double? {
    val real = chords.indices.filter { parse(chords[it].label).isChord }
    if (real.isEmpty()) return null
    val tokens = real.map { token(chords[it].label, key)!! }
    val observed = ArrayList<Double>()
    for ((j, at) in real.withIndex()) {
        val history = real.subList(0, j + 1).map { chords[it].label }
        val beatsSoFar = if (hasBeats) real.subList(0, j + 1).map { (chords[it].beats ?: 0).toDouble() } else null
        val (predictions, prior) = predictNext(models.lm, history, key, k = 3, historyBeats = beatsSoFar)
        var surprise: Double? = null
        if (j > 0 && tokens[j] != tokens[j - 1]) {
            val (distribution, _) = models.lm.distribution(tokens.subList(0, j))
            val p = maxOf(distribution[tokens[j]] ?: 0.0, 1e-6)
            surprise = pyRound(-log2(p), 3)
            observed += p
        }
        chords[at] = chords[at].copy(next = predictions, theoryOnly = prior, surprise = surprise)
    }
    return if (observed.isNotEmpty()) pyRound(observed.average(), 4) else null
}

/** The summary of [chords]: unique chords, time share and patterns, with the given [predictability]. */
internal fun summaryOf(chords: List<ChordSegment>, predictability: Double?): Summary {
    val real = chords.indices.filter { parse(chords[it].label).isChord }
    val share = LinkedHashMap<String, Double>()
    for (i in real) share[chords[i].display] = (share[chords[i].display] ?: 0.0) + (chords[i].end - chords[i].start)
    val total = share.values.sum().takeIf { it != 0.0 } ?: 1.0
    val romans = real.mapNotNull { chords[it].romanTriad }.filter { it.isNotEmpty() } // loops match bare numerals: V7 counts as V
    return Summary(
        uniqueChords = share.size,
        timeShare = share.entries.sortedByDescending { it.value }
            .map { (display, seconds) -> TimeShare(display, pyRound(seconds, 3), pyRound(seconds / total, 4)) },
        patterns = findPatterns(romans).map { Pattern(it.roman, it.name, it.count) },
        predictability = predictability,
    )
}

/** _bars: the chords sounding in each bar, a pickup bar first when the first downbeat is late.
 *  Bars shorter than [minLength] are dropped. */
internal fun bars(downbeats: DoubleArray, end: Double, chords: List<ChordSegment>, minLength: Double = 1e-3): List<Bar> {
    val bounds = ArrayList(downbeats.toList()).apply { add(end) }
    if (bounds[0] > 0.3) bounds.add(0, 0.0) // pickup before the first downbeat
    val out = ArrayList<Bar>()
    for (i in 0 until bounds.size - 1) {
        val start = bounds[i]
        val stop = bounds[i + 1]
        if (stop - start < minLength) continue
        val names = ArrayList<String>()
        for (chord in chords) {
            val overlap = minOf(stop, chord.end) - maxOf(start, chord.start)
            if (overlap >= 0.25 * (stop - start) && (names.isEmpty() || names.last() != chord.display)) names += chord.display
        }
        out += Bar(pyRound(start, 3), pyRound(stop, 3), names)
    }
    return out
}

/** Python's f"{x:.0%}". */
internal fun percent(x: Double): String = "${pyRound(x * 100, 0).toLong()}%"

private fun FloatArray.toDouble(): DoubleArray = DoubleArray(size) { this[it].toDouble() }

private fun checkCancelled(isCancelled: () -> Boolean) {
    if (isCancelled()) throw CancelledException()
}
