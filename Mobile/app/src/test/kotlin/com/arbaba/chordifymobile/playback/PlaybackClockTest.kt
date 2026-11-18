package com.arbaba.chordifymobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackClockTest {
    private var now = 0L

    private class FakePlayer : AudioPlayer {
        var position = 0.0
        var currentSpeed = 1.0
        var playing = false
        override val positionSeconds get() = position
        override var onEnded: (() -> Unit)? = null
        override fun play() { playing = true }
        override fun pause() { playing = false }
        override fun seekTo(seconds: Double) { position = seconds }
        override fun setSpeed(speed: Double) { currentSpeed = speed }
        override fun release() {}
    }

    private fun clock(duration: Double) = PlaybackClock(duration) { now }

    private fun advance(seconds: Double) { now += (seconds * 1e9).toLong() }

    @Test
    fun withoutAudioTheClockRunsAtItsRateAndStopsAtTheEnd() {
        val clock = clock(10.0)
        clock.play()
        advance(2.0)
        clock.tick()
        assertEquals(2.0, clock.time.value, 1e-9)
        clock.setRate(0.5)
        advance(2.0)
        clock.tick()
        assertEquals(3.0, clock.time.value, 1e-9)
        advance(100.0)
        clock.tick()
        assertEquals(10.0, clock.time.value, 1e-9)
        assertFalse(clock.playing.value)
    }

    @Test
    fun playingAtTheEndStartsAgain() {
        val clock = clock(10.0)
        clock.seek(9.99)
        clock.play()
        assertEquals(0.0, clock.time.value, 0.0)
    }

    @Test
    fun aPausedClockDoesNotMove() {
        val clock = clock(10.0)
        clock.play()
        clock.pause()
        advance(3.0)
        clock.tick()
        assertEquals(0.0, clock.time.value, 0.0)
    }

    @Test
    fun withAPlayerTheClockFollowsItsPosition() {
        val clock = clock(10.0)
        clock.setRate(0.75)
        clock.seek(4.0)
        val player = FakePlayer()
        clock.attach(player)
        assertEquals(4.0, player.position, 0.0)
        assertEquals(0.75, player.currentSpeed, 0.0)
        clock.play()
        assertTrue(player.playing)
        player.position = 4.6
        clock.tick()
        assertEquals(4.6, clock.time.value, 0.0)
        clock.seek(20.0)
        assertEquals(10.0, player.position, 0.0)
        player.onEnded!!()
        assertFalse(clock.playing.value)
        assertFalse(player.playing)
    }
}
