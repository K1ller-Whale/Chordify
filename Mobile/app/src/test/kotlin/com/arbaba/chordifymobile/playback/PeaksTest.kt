package com.arbaba.chordifymobile.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PeaksTest {
    @Test
    fun eachHopKeepsItsLoudestSample() {
        val sink = PeakSink(hopSamples = 3)
        sink.write(floatArrayOf(0.1f, -0.5f, 0.2f, 0.3f), 4)
        sink.write(floatArrayOf(-0.1f, 0.05f, 0.9f, 7f), 3) // only the first three count
        assertArrayEquals(floatArrayOf(0.5f, 0.3f, 0.9f), sink.finish(), 0f)
    }

    @Test
    fun aPartialLastHopIsKept() {
        val sink = PeakSink(hopSamples = 4)
        sink.write(floatArrayOf(0.2f, 0.1f, 0.4f, 0.3f, -0.6f), 5)
        assertArrayEquals(floatArrayOf(0.4f, 0.6f), sink.finish(), 0f)
    }

    @Test
    fun peaksAreMergedAndScaledToTheLoudest() {
        assertArrayEquals(floatArrayOf(0.5f, 1f, 0.25f), reducePeaks(floatArrayOf(0.1f, 0.2f, 0.4f, 0.3f, 0.1f), 2), 1e-6f)
        assertArrayEquals(floatArrayOf(0.25f, 1f), reducePeaks(floatArrayOf(0.1f, 0.4f), 1), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f), reducePeaks(floatArrayOf(0f, 0f), 1), 0f)
    }

    @Test
    fun theStepGrowsWithLongSongsAsOnTheWeb() {
        assertEquals(0.05, peakStep(8.0), 0.0)
        assertEquals(0.1, peakStep(300.0), 1e-12)
        assertEquals(1, mergeFactor(8.0))
        assertEquals(2, mergeFactor(300.0))
        assertEquals(6, mergeFactor(900.0))
    }
}
