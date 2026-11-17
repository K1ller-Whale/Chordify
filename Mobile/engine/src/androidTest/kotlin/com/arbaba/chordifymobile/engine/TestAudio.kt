package com.arbaba.chordifymobile.engine

import androidx.test.platform.app.InstrumentationRegistry
import com.arbaba.chordifymobile.engine.audio.SampleSink
import com.arbaba.chordifymobile.engine.util.pyRoundToInt
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Audio for the device tests: the golden inputs, and WAV files written on the spot. */
object TestAudio {
    private val cacheDir: File
        get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    /** goldens/audio/<name> from the test APK, copied to a file that MediaExtractor can open. */
    fun golden(name: String): File {
        val file = File(cacheDir, "goldens/$name")
        if (!file.exists()) {
            file.parentFile!!.mkdirs()
            val stream = TestAudio::class.java.getResourceAsStream("/goldens/audio/$name")
                ?: error("goldens/audio/$name is missing: run tools/mobile_goldens.sh --render-audio")
            stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        return file
    }

    /** An empty place in the cache for a test to write [name]. */
    fun tempFile(name: String): File = File(cacheDir, name).also { it.delete() }

    class Wav(val sampleRate: Int, val channels: Int, val mono: FloatArray)

    /** Reads a 16-bit PCM WAV and mixes it to mono as libsndfile and chordify_core.audio.to_mono
     *  do: every sample / 32768, then the mean of the channels in float32. */
    fun readWav(file: File): Wav {
        val bytes = file.readBytes()
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(text(bytes, 0) == "RIFF" && text(bytes, 8) == "WAVE") { "$file is not a WAV file" }
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var start = -1
        var size = 0
        var at = 12
        while (at + 8 <= bytes.size) {
            val length = data.getInt(at + 4)
            when (text(bytes, at)) {
                "fmt " -> {
                    channels = data.getShort(at + 10).toInt()
                    sampleRate = data.getInt(at + 12)
                    bits = data.getShort(at + 22).toInt()
                }
                "data" -> {
                    start = at + 8
                    size = length
                }
            }
            at += 8 + length + (length and 1)
        }
        require(bits == 16 && start >= 0) { "$file is not 16-bit PCM" }
        val mono = FloatArray(size / (2 * channels)) { frame ->
            var sum = 0f
            for (channel in 0 until channels) sum += data.getShort(start + 2 * (frame * channels + channel)) / 32768f
            sum / channels
        }
        return Wav(sampleRate, channels, mono)
    }

    /** Writes [mono] (−1 to 1) as a 16-bit mono WAV. */
    fun writeWav(file: File, mono: FloatArray, sampleRate: Int) {
        val data = ByteBuffer.allocate(44 + 2 * mono.size).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + 2 * mono.size).put("WAVE".toByteArray(Charsets.US_ASCII))
        data.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1.toShort()).putShort(1.toShort())
            .putInt(sampleRate).putInt(2 * sampleRate).putShort(2.toShort()).putShort(16.toShort())
        data.put("data".toByteArray(Charsets.US_ASCII)).putInt(2 * mono.size)
        for (value in mono) data.putShort((value * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
        file.writeBytes(data.array())
    }

    /** Hands [samples] to [sink] in chunks of at most [chunk] values. */
    fun feed(sink: SampleSink, samples: FloatArray, chunk: Int) {
        var at = 0
        while (at < samples.size) {
            val count = minOf(chunk, samples.size - at)
            sink.write(samples.copyOfRange(at, at + count), count)
            at += count
        }
    }

    fun concat(parts: List<FloatArray>): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    /** chordify_ai.export.mobile_goldens.envelope_offset_ms: how late [other] is against
     *  [reference], in ms, from the lag (within ±200 ms) that best lines up their 5 ms RMS envelopes. */
    fun envelopeOffsetMs(reference: FloatArray, other: FloatArray, sampleRate: Int): Double {
        val hop = pyRoundToInt(sampleRate * 0.005)
        fun envelope(y: FloatArray): DoubleArray {
            val e = DoubleArray(y.size / hop) { i ->
                var sum = 0.0
                for (k in i * hop until (i + 1) * hop) sum += y[k].toDouble() * y[k]
                sqrt(sum / hop)
            }
            val mean = e.average()
            return DoubleArray(e.size) { e[it] - mean }
        }
        val a = envelope(reference)
        val b = envelope(other)
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in -40..40) {
            val (x0, y0) = if (lag >= 0) 0 to lag else -lag to 0
            var score = 0.0
            for (i in 0 until minOf(a.size - x0, b.size - y0)) score += a[x0 + i] * b[y0 + i]
            if (score > bestScore) {
                best = lag
                bestScore = score
            }
        }
        return best * hop * 1000.0 / sampleRate
    }

    private fun text(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
}
