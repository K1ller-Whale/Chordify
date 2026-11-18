package com.arbaba.chordifymobile.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min

/** What plays the audio: Media3 in the app, a fake in the JVM tests. */
interface AudioPlayer {
    /** Where playback is, in seconds. ExoPlayer's position already allows for the output latency. */
    val positionSeconds: Double
    var onEnded: (() -> Unit)?
    fun play()
    fun pause()
    fun seekTo(seconds: Double)
    fun setSpeed(speed: Double)
    fun release()
}

/** One playback clock for the analysis screen (chordify-frontend/src/lib/clock.ts, spec §6.3).
 *  With a player attached it follows the player's position; without one it runs a simulated
 *  clock, so a result can still be explored while its audio file is missing. The screen calls
 *  [tick] once a frame while [playing]; state changes only when the time does. */
class PlaybackClock(val duration: Double, private val nanoTime: () -> Long = System::nanoTime) {
    private val _time = MutableStateFlow(0.0)
    private val _playing = MutableStateFlow(false)
    private val _rate = MutableStateFlow(1.0)
    val time: StateFlow<Double> = _time.asStateFlow()
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    val rate: StateFlow<Double> = _rate.asStateFlow()

    var player: AudioPlayer? = null
        private set
    private var lastTick = 0L

    fun attach(player: AudioPlayer?) {
        this.player?.onEnded = null
        this.player = player
        if (player != null) {
            player.setSpeed(_rate.value)
            player.seekTo(_time.value)
            player.onEnded = { pause() }
            if (_playing.value) player.play()
        }
    }

    fun tick() {
        if (!_playing.value) return
        val now = nanoTime()
        val current = player
        if (current != null) {
            _time.value = maxOf(0.0, current.positionSeconds)
        } else {
            _time.value = min(duration, _time.value + (now - lastTick) / 1e9 * _rate.value)
            if (_time.value >= duration) _playing.value = false
        }
        lastTick = now
    }

    fun play() {
        if (_playing.value) return
        if (_time.value >= duration - 0.05) seek(0.0)
        _playing.value = true
        lastTick = nanoTime()
        player?.play()
    }

    fun pause() {
        _playing.value = false
        player?.pause()
    }

    fun toggle() = if (_playing.value) pause() else play()

    fun seek(seconds: Double) {
        _time.value = seconds.coerceIn(0.0, if (duration > 0) duration else seconds)
        player?.seekTo(_time.value)
    }

    fun setRate(rate: Double) {
        _rate.value = rate
        player?.setSpeed(rate)
    }

    companion object {
        /** The speeds the transport offers (spec §6.3), pitch kept. */
        val SPEEDS = listOf(0.5, 0.75, 1.0, 1.25)
    }
}

/** Media3 ExoPlayer on a file URI. Create, use and release it on the main thread. */
class ExoAudioPlayer(context: Context, uri: Uri) : AudioPlayer {
    private val player: ExoPlayer = ExoPlayer.Builder(context).build()
    override var onEnded: (() -> Unit)? = null

    init {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded?.invoke()
            }
        })
        player.prepare()
    }

    override val positionSeconds: Double get() = player.currentPosition / 1000.0

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun seekTo(seconds: Double) = player.seekTo((seconds * 1000).toLong())

    /** ExoPlayer keeps the pitch when only the speed changes. */
    override fun setSpeed(speed: Double) = player.setPlaybackSpeed(speed.toFloat())

    override fun release() = player.release()
}
