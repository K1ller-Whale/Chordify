package com.arbaba.chordifymobile.engine.features

import com.arbaba.chordifymobile.engine.NativeLibrary
import com.arbaba.chordifymobile.engine.audio.SampleSink

/** NNLS Chroma (Mauch & Dixon) as the server runs it: the Vamp plugin, driven by a native host
 *  that frames, windows and transforms the audio exactly as the Python `vamp` package does
 *  (src/main/cpp/nnls_host.h). Write mono audio at [sampleRate], then [finish] once.
 *  Not thread-safe; [close] frees the native memory. */
class NnlsChroma(
    sampleRate: Int = NNLS_BOTHCHROMA.sampleRate,
    output: String = "bothchroma",
) : SampleSink, AutoCloseable {
    private var handle = NnlsNative.create(sampleRate.toFloat(), output)
    private var finished = false

    override fun write(samples: FloatArray, count: Int) {
        check(handle != 0L && !finished) { "NnlsChroma is finished or closed" }
        require(count in 0..samples.size) { "count $count is outside 0..${samples.size}" }
        NnlsNative.write(handle, samples, count)
    }

    /** One row per frame, in the plugin's bin order: A first ([nnlsToCOrder] makes it C first). */
    fun finish(): Array<FloatArray> {
        check(handle != 0L && !finished) { "NnlsChroma is finished or closed" }
        finished = true
        return NnlsNative.finish(handle)
    }

    override fun close() {
        if (handle != 0L) {
            NnlsNative.release(handle)
            handle = 0L
        }
    }
}

/** src/main/cpp/nnls_jni.cpp. */
internal object NnlsNative {
    init {
        NativeLibrary.load()
    }

    @JvmStatic external fun create(sampleRate: Float, output: String): Long
    @JvmStatic external fun write(handle: Long, samples: FloatArray, count: Int)
    @JvmStatic external fun finish(handle: Long): Array<FloatArray>
    @JvmStatic external fun release(handle: Long)
}
