package com.arbaba.chordifymobile.engine.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.arbaba.chordifymobile.engine.CancelledException
import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import java.io.IOException

/** The first audio track of a file: its own sample rate and channel count, and how many mono frames it gave. */
internal data class DecodedTrack(val sampleRate: Int, val channels: Int, val frames: Long)

/** Decodes the first audio track of a file with MediaExtractor and MediaCodec (mp3, m4a/aac, flac,
 *  ogg, opus, wav, webm), mixes it to mono and hands it on chunk by chunk at the track's own sample
 *  rate, as chordify_core.audio.load_audio does before it resamples. */
internal class AudioDecoder(private val source: AudioSource) {
    /** Calls [onStart] once with the sample rate, then [onSamples] with every chunk. */
    fun decode(isCancelled: () -> Boolean, onStart: (sampleRate: Int) -> Unit, onSamples: (FloatArray) -> Unit): DecodedTrack {
        val extractor = MediaExtractor()
        try {
            try {
                source.openIn(extractor)
            } catch (error: IOException) {
                throw unsupported("The file is not a supported audio format.", error)
            } catch (error: IllegalArgumentException) {
                throw unsupported("The file is not a supported audio format.", error)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw unsupported("The file has no audio track.", null)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = openCodec(format)
            try {
                return pump(extractor, codec, format, isCancelled, onStart, onSamples)
            } catch (error: IllegalStateException) { // MediaCodec.CodecException is one
                throw EngineException(ErrorCode.DECODE_FAILED, "Could not decode the file: ${error.message}", error)
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun openCodec(format: MediaFormat): MediaCodec {
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (error: IOException) {
            throw unsupported("Android has no decoder for $mime.", error)
        } catch (error: IllegalArgumentException) {
            throw unsupported("Android has no decoder for $mime.", error)
        }
        try {
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (error: RuntimeException) {
            codec.release()
            throw unsupported("Android cannot decode this $mime track.", error)
        }
        return codec
    }

    private fun pump(
        extractor: MediaExtractor,
        codec: MediaCodec,
        format: MediaFormat,
        isCancelled: () -> Boolean,
        onStart: (Int) -> Unit,
        onSamples: (FloatArray) -> Unit,
    ): DecodedTrack {
        val trimmer = EdgeTrimmer(format.intOr(MediaFormat.KEY_ENCODER_DELAY), format.intOr(MediaFormat.KEY_ENCODER_PADDING))
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var sampleRate = 0
        var channels = 0
        var frames = 0L
        var idle = 0
        while (true) {
            if (isCancelled()) throw CancelledException()
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (index < 0) { // nothing yet, or a format change, which every buffer's own format covers
                if (inputDone && ++idle > MAX_IDLE) {
                    throw EngineException(ErrorCode.DECODE_FAILED, "The decoder stopped producing audio.")
                }
                continue
            }
            idle = 0
            val output = codec.getOutputFormat(index)
            val rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            if (sampleRate == 0) {
                sampleRate = rate
                channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                onStart(sampleRate)
            } else if (rate != sampleRate) {
                throw EngineException(ErrorCode.DECODE_FAILED, "The sample rate changes in the middle of the file.")
            }
            val encoding = output.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val buffer = codec.getOutputBuffer(index)!!
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val mono = trimmer.push(Pcm.toMono(buffer, encoding, output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)))
            codec.releaseOutputBuffer(index, false)
            if (mono.isNotEmpty()) {
                frames += mono.size
                onSamples(mono)
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return DecodedTrack(sampleRate, channels, frames)
        }
    }

    private fun MediaFormat.intOr(key: String, default: Int = 0) = if (containsKey(key)) getInteger(key) else default

    private fun unsupported(message: String, cause: Throwable?) = EngineException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, message, cause)

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE = 500 // 5 s without output after the end of the input
    }
}
