package com.arbaba.chordifymobile.engine.audio

import com.arbaba.chordifymobile.engine.NativeLibrary
import kotlin.math.ceil

/** Resamples a mono stream as `librosa.resample(y, orig_sr=inRate, target_sr=outRate)` does by
 *  default: libsoxr at high quality (`res_type="soxr_hq"`), then the result cut or zero-padded to
 *  `ceil(n × outRate / inRate)` samples (`fix=True`). Chunks may have any size. The newest
 *  output is held back until [finish], so the length can still be fixed. */
class Resampler internal constructor(
    val inRate: Int,
    val outRate: Int,
    private val core: ResamplerCore?,
) : AutoCloseable {
    constructor(inRate: Int, outRate: Int) : this(inRate, outRate, if (inRate == outRate) null else SoxrCore(inRate, outRate))

    private var inputs = 0L
    private var emitted = 0L
    private var held = FloatArray(0)
    private var finished = false

    /** Output for the first [count] values of [input]. */
    fun process(input: FloatArray, count: Int = input.size): FloatArray {
        check(!finished) { "process() after finish()" }
        inputs += count
        if (core == null) {
            emitted += count
            return input.copyOf(count)
        }
        val all = held + core.process(input, count)
        val release = maxOf(0, all.size - HOLDBACK)
        held = all.copyOfRange(release, all.size)
        emitted += release
        return all.copyOf(release)
    }

    /** The rest of the output. Afterwards exactly ceil(n × outRate / inRate) samples have come out. */
    fun finish(): FloatArray {
        check(!finished) { "finish() called twice" }
        finished = true
        if (core == null) return FloatArray(0)
        val total = ceil(inputs * (outRate.toDouble() / inRate)).toLong()
        check(total >= emitted) { "$emitted samples already out, more than the $total librosa keeps" }
        return (held + core.flush()).copyOf((total - emitted).toInt())
    }

    override fun close() {
        core?.close()
    }

    private companion object {
        // libsoxr never produces more than ceil(n × ratio) samples (checked against python-soxr
        // 1.1.0 for rates from 8 to 96 kHz), so holding back 64 is plenty.
        const val HOLDBACK = 64
    }
}

/** The resampling engine behind [Resampler]: libsoxr in the app, stand-ins in JVM tests. */
internal interface ResamplerCore : AutoCloseable {
    fun process(input: FloatArray, count: Int): FloatArray
    fun flush(): FloatArray
}

/** libsoxr through src/main/cpp/soxr_jni.cpp. */
private class SoxrCore(inRate: Int, outRate: Int) : ResamplerCore {
    private var handle = SoxrNative.create(inRate.toDouble(), outRate.toDouble())

    override fun process(input: FloatArray, count: Int): FloatArray = SoxrNative.process(handle, input, count)

    override fun flush(): FloatArray = SoxrNative.flush(handle)

    override fun close() {
        if (handle != 0L) {
            SoxrNative.release(handle)
            handle = 0L
        }
    }
}

internal object SoxrNative {
    init {
        NativeLibrary.load()
    }

    @JvmStatic external fun create(inRate: Double, outRate: Double): Long
    @JvmStatic external fun process(handle: Long, input: FloatArray, count: Int): FloatArray
    @JvmStatic external fun flush(handle: Long): FloatArray
    @JvmStatic external fun release(handle: Long)
}
