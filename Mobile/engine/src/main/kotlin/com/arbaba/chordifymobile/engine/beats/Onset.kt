package com.arbaba.chordifymobile.engine.beats

import com.arbaba.chordifymobile.engine.audio.SampleSink
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10

// Port of librosa 1.0.1's onset_strength(y, sr=22050, hop_length=512) as
// chordify_backend.app.pipeline.beats.track_beats calls it: a centred, zero-padded STFT
// (n_fft 2048, periodic Hann), a 128-band Slaney mel power spectrogram, power_to_db
// (ref 1, amin 1e-10, top_db 80 below the song's peak), then the mean positive difference
// between consecutive frames, shifted 3 frames late.

const val ONSET_SAMPLE_RATE = 22050
const val ONSET_HOP = 512
const val ONSET_N_FFT = 2048
const val ONSET_N_MELS = 128

/** scipy.signal.get_window("hann", n, fftbins=True): the periodic Hann window. */
fun hann(n: Int): DoubleArray = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }

// The Slaney mel scale, with librosa's constants computed as librosa computes them.
private const val MEL_F_SP = 200.0 / 3
private const val MEL_MIN_LOG_HZ = 1000.0
private const val MEL_MIN_LOG_MEL = MEL_MIN_LOG_HZ / MEL_F_SP
private val MEL_LOGSTEP = ln(6.4) / 27.0

/** librosa.hz_to_mel with htk=False (Slaney: linear below 1 kHz, logarithmic above). */
fun hzToMel(hz: Double): Double =
    if (hz >= MEL_MIN_LOG_HZ) MEL_MIN_LOG_MEL + ln(hz / MEL_MIN_LOG_HZ) / MEL_LOGSTEP else hz / MEL_F_SP

/** librosa.mel_to_hz with htk=False. */
fun melToHz(mel: Double): Double =
    if (mel >= MEL_MIN_LOG_MEL) MEL_MIN_LOG_HZ * exp(MEL_LOGSTEP * (mel - MEL_MIN_LOG_MEL)) else MEL_F_SP * mel

/** librosa.filters.mel(sr, n_fft, n_mels) with fmin 0, fmax sr / 2 and Slaney normalisation,
 *  as float32: [nMels][1 + nFft / 2]. */
fun melFilters(sampleRate: Int, nFft: Int, nMels: Int = ONSET_N_MELS): Array<FloatArray> {
    val bins = 1 + nFft / 2
    val fftFreqs = DoubleArray(bins) { it * (sampleRate.toDouble() / nFft) }
    val minMel = hzToMel(0.0)
    val maxMel = hzToMel(sampleRate / 2.0)
    val step = (maxMel - minMel) / (nMels + 1) // numpy.linspace(minMel, maxMel, nMels + 2)
    val melF = DoubleArray(nMels + 2) { melToHz(if (it == nMels + 1) maxMel else it * step + minMel) }
    return Array(nMels) { i ->
        val enorm = 2.0 / (melF[i + 2] - melF[i])
        FloatArray(bins) { f ->
            val lower = -(melF[i] - fftFreqs[f]) / (melF[i + 1] - melF[i])
            val upper = (melF[i + 2] - fftFreqs[f]) / (melF[i + 2] - melF[i + 1])
            // numpy stores the weights as float32, then scales them by the float64 norm in place
            (maxOf(0.0, minOf(lower, upper)).toFloat().toDouble() * enorm).toFloat()
        }
    }
}

/** Onset strength of a mono 22.05 kHz stream, written chunk by chunk; [finish] returns the
 *  envelope, one value per 512-sample frame (1 + n / 512 of them). */
class OnsetStrength : SampleSink {
    private val fft = Fft(ONSET_N_FFT)
    private val window = hann(ONSET_N_FFT)
    private val filters = melFilters(ONSET_SAMPLE_RATE, ONSET_N_FFT)
    private val re = DoubleArray(ONSET_N_FFT)
    private val im = DoubleArray(ONSET_N_FFT)
    private val power = FloatArray(ONSET_N_FFT / 2 + 1)
    private val mel = ArrayList<FloatArray>() // mel power, one row per frame
    private var buffer = FloatArray(4 * ONSET_N_FFT)
    private var bufferStart = 0L // sample index of buffer[0]
    private var bufferEnd = 0L // samples received
    private var nextFrame = 0L

    override fun write(samples: FloatArray, count: Int) {
        ensureRoom(count)
        samples.copyInto(buffer, (bufferEnd - bufferStart).toInt(), 0, count)
        bufferEnd += count
        // frame t covers samples [512 t - 1024, 512 t + 1024): complete once 512 t + 1024 have arrived
        while (nextFrame * ONSET_HOP + ONSET_N_FFT / 2 <= bufferEnd) frame(nextFrame++)
    }

    fun finish(): FloatArray {
        val frames = 1 + bufferEnd / ONSET_HOP
        while (nextFrame < frames) frame(nextFrame++)
        return envelope(mel)
    }

    private fun ensureRoom(count: Int) {
        val keepFrom = maxOf(bufferStart, nextFrame * ONSET_HOP - ONSET_N_FFT / 2) // first sample still needed
        val kept = (bufferEnd - keepFrom).toInt()
        if (keepFrom > bufferStart) {
            buffer.copyInto(buffer, 0, (keepFrom - bufferStart).toInt(), (bufferEnd - bufferStart).toInt())
            bufferStart = keepFrom
        }
        if (kept + count > buffer.size) buffer = buffer.copyOf(maxOf(2 * buffer.size, kept + count))
    }

    private fun frame(t: Long) {
        val first = t * ONSET_HOP - ONSET_N_FFT / 2
        for (j in 0 until ONSET_N_FFT) {
            val index = first + j
            val sample = if (index in bufferStart until bufferEnd) buffer[(index - bufferStart).toInt()].toDouble() else 0.0
            re[j] = window[j] * sample
            im[j] = 0.0
        }
        fft.transform(re, im)
        for (f in power.indices) {
            // librosa keeps the STFT as complex64, so the magnitude is taken in float32
            val magnitude = hypot(re[f].toFloat().toDouble(), im[f].toFloat().toDouble()).toFloat()
            power[f] = magnitude * magnitude
        }
        mel += FloatArray(ONSET_N_MELS) { m ->
            val weights = filters[m]
            var sum = 0.0
            for (f in power.indices) if (weights[f] != 0f) sum += weights[f].toDouble() * power[f]
            sum.toFloat()
        }
    }

    private companion object {
        const val LAG = 1
        const val PAD = LAG + ONSET_N_FFT / (2 * ONSET_HOP) // 3: the lag plus the centring

        fun envelope(mel: List<FloatArray>): FloatArray {
            // power_to_db as librosa 1.0.1 computes it on a float32 array: log10 in place, then × 10
            val db = Array(mel.size) { t -> FloatArray(ONSET_N_MELS) { m -> log10(maxOf(1e-10f, mel[t][m]).toDouble()).toFloat() * 10f } }
            val peak = db.maxOfOrNull { row -> row.max() } ?: 0f
            for (row in db) for (m in row.indices) row[m] = maxOf(row[m], peak - 80f)
            return FloatArray(mel.size) { t ->
                if (t < PAD) 0f else {
                    var sum = 0.0
                    for (m in 0 until ONSET_N_MELS) sum += maxOf(0f, db[t - PAD + LAG][m] - db[t - PAD][m])
                    (sum / ONSET_N_MELS).toFloat()
                }
            }
        }
    }
}

/** The onset strength of a whole signal at 22.05 kHz. */
fun onsetStrength(y: FloatArray): FloatArray = OnsetStrength().also { it.write(y, y.size) }.finish()
