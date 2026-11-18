package com.arbaba.chordifymobile.engine.beats

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Radix-2 complex FFT in double precision, for a size that is a power of two. librosa's
 *  spectra come from scipy.fft in double precision too, so the results agree to rounding. */
class Fft(val size: Int) {
    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size $size is not a power of two" }
    }

    private val cosines = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sines = DoubleArray(size / 2) { sin(2 * PI * it / size) }
    private val reversed = IntArray(size).also { table ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) table[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** In place: [re] and [im] become the transform (forward: e^-2πi kn/N; inverse also divides by N). */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        val sign = if (inverse) 1.0 else -1.0
        var length = 2
        while (length <= size) {
            val half = length / 2
            val stride = size / length
            for (start in 0 until size step length) {
                for (k in 0 until half) {
                    val wr = cosines[k * stride]
                    val wi = sign * sines[k * stride]
                    val a = start + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }
            }
            length *= 2
        }
        if (inverse) {
            for (i in 0 until size) {
                re[i] /= size
                im[i] /= size
            }
        }
    }

    /** numpy.fft.rfft of a real signal of [size] values: bins 0..size/2, as (real parts, imaginary parts). */
    fun rfft(signal: DoubleArray): Pair<DoubleArray, DoubleArray> {
        require(signal.size == size) { "signal has ${signal.size} values, expected $size" }
        val re = signal.copyOf()
        val im = DoubleArray(size)
        transform(re, im)
        return re.copyOf(size / 2 + 1) to im.copyOf(size / 2 + 1)
    }
}
