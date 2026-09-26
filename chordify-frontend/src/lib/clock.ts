import { useSyncExternalStore } from 'react'

/**
 * One playback clock for the whole analysis screen (plan 06 §4.1).
 *
 * With an <audio> element attached it follows ``currentTime`` (minus the output latency,
 * so highlights change when a chord is heard); without audio it runs a simulated clock so
 * a result can still be explored. Components subscribe; React state only changes when a
 * selected value (e.g. the chord index) changes, and 60 fps motion is done imperatively.
 */
export class PlaybackClock {
  time = 0
  playing = false
  duration = 0
  rate = 1
  private audio: HTMLAudioElement | null = null
  private latency = 0
  private listeners = new Set<() => void>()
  private frame = 0
  private lastTick = 0

  attach(audio: HTMLAudioElement | null, outputLatency = 0) {
    this.audio = audio
    this.latency = outputLatency
    if (audio) {
      audio.playbackRate = this.rate
      audio.preservesPitch = true
      audio.currentTime = this.time
      audio.onended = () => this.pause()
    }
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  private emit() {
    for (const listener of this.listeners) listener()
  }

  private tick = (now: number) => {
    if (!this.playing) return
    if (this.audio) {
      this.time = Math.max(0, this.audio.currentTime - this.latency)
    } else {
      this.time = Math.min(this.duration, this.time + ((now - this.lastTick) / 1000) * this.rate)
      if (this.time >= this.duration) this.playing = false
    }
    this.lastTick = now
    this.emit()
    this.frame = requestAnimationFrame(this.tick)
  }

  play() {
    if (this.playing) return
    if (this.time >= this.duration - 0.05) this.seek(0)
    this.playing = true
    this.lastTick = performance.now()
    void this.audio?.play().catch(() => this.pause())
    this.frame = requestAnimationFrame(this.tick)
    this.emit()
  }

  pause() {
    this.playing = false
    cancelAnimationFrame(this.frame)
    this.audio?.pause()
    this.emit()
  }

  toggle() {
    if (this.playing) this.pause()
    else this.play()
  }

  seek(t: number) {
    this.time = Math.min(Math.max(0, t), this.duration || t)
    if (this.audio) this.audio.currentTime = this.time
    this.emit()
  }

  setRate(rate: number) {
    this.rate = rate
    if (this.audio) this.audio.playbackRate = rate
    this.emit()
  }

  dispose() {
    this.pause()
    this.listeners.clear()
  }
}

/** Re-render only when ``select(clock)`` changes. */
export function useClock<T>(clock: PlaybackClock, select: (clock: PlaybackClock) => T): T {
  return useSyncExternalStore(clock.subscribe, () => select(clock))
}
