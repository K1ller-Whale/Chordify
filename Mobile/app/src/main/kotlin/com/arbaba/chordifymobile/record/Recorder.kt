package com.arbaba.chordifymobile.record

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** A 16-bit PCM mono WAV file written as samples arrive; the sizes in its header are filled in
 *  when it is closed, so whatever was written before a failure is still a valid file. */
class WavWriter(file: File, private val sampleRate: Int) : Closeable {
    private val out = RandomAccessFile(file, "rw").apply { setLength(0); write(header(0, sampleRate)) }

    var samples = 0L
        private set

    fun write(pcm: ShortArray, count: Int) {
        val bytes = ByteArray(2 * count)
        for (i in 0 until count) {
            bytes[2 * i] = pcm[i].toInt().toByte()
            bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
        }
        out.write(bytes)
        samples += count
    }

    override fun close() {
        try {
            out.seek(0)
            out.write(header(samples, sampleRate))
        } finally {
            out.close()
        }
    }

    companion object {
        /** The 44-byte RIFF header of a 16-bit mono file with [samples] samples. */
        fun header(samples: Long, sampleRate: Int): ByteArray {
            val dataBytes = 2 * samples
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate)
                .putInt(2 * sampleRate).putShort(2).putShort(16)
            header.put("data".toByteArray()).putInt(dataBytes.toInt())
            return header.array()
        }
    }
}

/** The level of [count] samples, 0 to 1: their RMS over full scale. */
fun rms(pcm: ShortArray, count: Int): Float {
    if (count <= 0) return 0f
    var sum = 0.0
    for (i in 0 until count) sum += pcm[i].toDouble() * pcm[i]
    return (kotlin.math.sqrt(sum / count) / 32768.0).toFloat()
}

/** Recording from the microphone as the spec asks (§6.1): 44.1 kHz, 16-bit mono, from the
 *  UNPROCESSED source where the phone has one, else VOICE_RECOGNITION. */
object Recorder {
    const val SAMPLE_RATE = 44100

    fun source(context: Context): Int {
        val audio = context.getSystemService(AudioManager::class.java)
        return if (audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    /** Records into [file] until [isStopped] returns true, reporting the seconds recorded. Stops
     *  early, keeping what it has, when the file cannot grow (storage full). Returns the samples
     *  written. Blocks: call it off the main thread. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun record(context: Context, file: File, isStopped: () -> Boolean, onLevel: (Float) -> Unit = {},
               onSeconds: (Double) -> Unit): Long {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val chunk = maxOf(minimum, SAMPLE_RATE / 10) // a tenth of a second
        val record = AudioRecord(source(context), SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                                 AudioFormat.ENCODING_PCM_16BIT, 2 * chunk)
        check(record.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not be opened." }
        val buffer = ShortArray(chunk)
        try {
            WavWriter(file, SAMPLE_RATE).use { wav ->
                record.startRecording()
                while (!isStopped()) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n < 0) break
                    try {
                        wav.write(buffer, n)
                    } catch (_: IOException) {
                        break // storage full: keep what was recorded
                    }
                    onLevel(rms(buffer, n))
                    onSeconds(wav.samples.toDouble() / SAMPLE_RATE)
                }
                return wav.samples
            }
        } finally {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            record.release()
        }
    }
}
