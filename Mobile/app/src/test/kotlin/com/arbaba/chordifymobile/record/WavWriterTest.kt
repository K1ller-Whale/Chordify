package com.arbaba.chordifymobile.record

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavWriterTest {
    @Test
    fun theHeaderDescribesSixteenBitMono() {
        val header = ByteBuffer.wrap(WavWriter.header(1000, 44100)).order(ByteOrder.LITTLE_ENDIAN)
        fun text(at: Int) = String(header.array(), at, 4, Charsets.US_ASCII)
        assertEquals("RIFF", text(0))
        assertEquals(36 + 2000, header.getInt(4))
        assertEquals("WAVE", text(8))
        assertEquals("fmt ", text(12))
        assertEquals(16, header.getInt(16))
        assertEquals(1.toShort(), header.getShort(20)) // PCM
        assertEquals(1.toShort(), header.getShort(22)) // mono
        assertEquals(44100, header.getInt(24))
        assertEquals(88200, header.getInt(28)) // bytes per second
        assertEquals(2.toShort(), header.getShort(32))
        assertEquals(16.toShort(), header.getShort(34))
        assertEquals("data", text(36))
        assertEquals(2000, header.getInt(40))
    }

    @Test
    fun samplesAreLittleEndianAndTheSizesAreFilledInOnClose() {
        val file = File.createTempFile("wav", ".wav").apply { deleteOnExit() }
        WavWriter(file, 22050).use { wav ->
            wav.write(shortArrayOf(1, -2, 0x1234), 3)
            wav.write(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 99), 2) // only the first two
            assertEquals(5L, wav.samples)
        }
        val bytes = file.readBytes()
        assertEquals(44 + 10, bytes.size)
        assertArrayEquals(WavWriter.header(5, 22050), bytes.copyOfRange(0, 44))
        val data = ByteBuffer.wrap(bytes, 44, 10).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(listOf<Short>(1, -2, 0x1234, Short.MAX_VALUE, Short.MIN_VALUE), List(5) { data.getShort() })
    }

    @Test
    fun anEmptyRecordingIsStillAValidFile() {
        val file = File.createTempFile("wav", ".wav").apply { deleteOnExit() }
        WavWriter(file, 44100).close()
        assertArrayEquals(WavWriter.header(0, 44100), file.readBytes())
    }
}
