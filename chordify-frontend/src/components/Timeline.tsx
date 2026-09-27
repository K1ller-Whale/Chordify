import { useEffect, useMemo, useRef } from 'react'
import type { AnalysisResult, ChordSegment } from '../api/types'
import { type PlaybackClock, useClock } from '../lib/clock'
import { chordName, formatTime, functionColor, isNoChord, type Notation } from '../lib/music'

const PPS = 70 // pixels per second in the detail track
const HEIGHT = 196
const Y = { sec: 6, secH: 22, beat: 34, rib: 44, ribH: 64, wave: 118, waveH: 70 }

interface Props {
  result: AnalysisResult
  clock: PlaybackClock
  current: number
  notation: Notation
  peaks: number[] | null
  surprising: Set<number>
}

export function Timeline({ result, clock, current, notation, peaks, surprising }: Props) {
  const duration = result.source.duration
  const detail = useRef<HTMLDivElement>(null)
  const track = useRef<SVGSVGElement>(null)
  const viewport = useRef<SVGRectElement>(null)
  const head = useRef<SVGRectElement>(null)
  const time = useRef<HTMLSpanElement>(null)
  const playing = useClock(clock, (c) => c.playing)
  const rate = useClock(clock, (c) => c.rate)

  useEffect(() => {
    const update = () => {
      const width = detail.current?.clientWidth ?? 800
      const headX = width * 0.33
      track.current?.style.setProperty('transform', `translateX(${headX - clock.time * PPS}px)`)
      viewport.current?.setAttribute('x', String(((clock.time - headX / PPS) / duration) * 1000))
      viewport.current?.setAttribute('width', String((width / PPS / duration) * 1000))
      head.current?.setAttribute('x', String((clock.time / duration) * 1000))
      if (time.current) time.current.textContent = `${formatTime(clock.time)} / ${formatTime(duration)}`
    }
    update()
    window.addEventListener('resize', update)
    const unsubscribe = clock.subscribe(update)
    return () => {
      unsubscribe()
      window.removeEventListener('resize', update)
    }
  }, [clock, duration])

  const waveform = useMemo(() => {
    if (!peaks) return ''
    const step = duration / peaks.length
    return peaks.map((p, i) => {
      const a = Math.max(0.5, p * (Y.waveH / 2 - 2))
      return `M${(i * step * PPS).toFixed(1)} ${(Y.wave + Y.waveH / 2 - a).toFixed(1)}V${(Y.wave + Y.waveH / 2 + a).toFixed(1)}`
    }).join('')
  }, [peaks, duration])

  const seekDetail = (event: React.MouseEvent<HTMLDivElement>) => {
    const box = event.currentTarget.getBoundingClientRect()
    clock.seek(clock.time + (event.clientX - box.left - box.width * 0.33) / PPS)
  }
  const seekOverview = (event: React.MouseEvent<HTMLDivElement>) => {
    const box = event.currentTarget.getBoundingClientRect()
    clock.seek(((event.clientX - box.left) / box.width) * duration)
  }
  const sx = (t: number) => (t / duration) * 1000

  return (
    <section className="card">
      <h2>Timeline</h2>
      <div className="overview" onClick={seekOverview} title="Whole song: click to jump">
        <svg viewBox="0 0 1000 34" preserveAspectRatio="none">
          {result.chords.map((c) => (
            <rect key={c.index} x={sx(c.start)} y={12} width={Math.max(0.5, sx(c.end) - sx(c.start) - 0.6)} height={18}
                  fill={isNoChord(c) ? 'var(--grid)' : functionColor(c.function)} />
          ))}
          {result.sections.map((s) => <rect key={s.start} x={sx(s.start)} y={0} width={0.8} height={34} fill="var(--ink-2)" />)}
          <rect ref={viewport} y={9} height={24} rx={3} fill="none" stroke="var(--ink)" strokeWidth={1.5} vectorEffect="non-scaling-stroke" />
          <rect ref={head} y={0} width={1.2} height={34} fill="var(--ink)" />
        </svg>
      </div>
      <div className="detail" ref={detail} onClick={seekDetail}>
        <svg ref={track} className="track" width={duration * PPS} height={HEIGHT} viewBox={`0 0 ${duration * PPS} ${HEIGHT}`}>
          <defs>
            <pattern id="hatch" width={6} height={6} patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
              <rect width={2} height={6} fill="var(--borrowed)" />
            </pattern>
          </defs>
          {result.sections.map((s, i) => (
            <g key={s.start}>
              <rect x={s.start * PPS + 1} y={Y.sec} width={(s.end - s.start) * PPS - 2} height={Y.secH} rx={4}
                    fill={i % 2 ? 'var(--surface-2)' : 'var(--grid)'} />
              <text x={s.start * PPS + 10} y={Y.sec + 15} fontSize={12} fontWeight={600} fill="var(--ink-2)">{s.letter} {s.label}</text>
            </g>
          ))}
          {result.beats.map((b) => <line key={`b${b}`} x1={b * PPS} x2={b * PPS} y1={Y.beat} y2={HEIGHT - 4} stroke="var(--grid)" />)}
          {result.downbeats.map((b, i) => (
            <g key={`d${b}`}>
              <line x1={b * PPS} x2={b * PPS} y1={Y.beat - 2} y2={HEIGHT - 4} stroke="var(--axis)" />
              {i % 4 === 0 && <text x={b * PPS + 3} y={Y.beat + 7} fontSize={9} fill="var(--muted)">{i + 1}</text>}
            </g>
          ))}
          {result.chords.map((c) => (
            <ChordBlock key={c.index} chord={c} active={c.index === current} notation={notation} surprising={surprising.has(c.index)} />
          ))}
          {waveform && <path d={waveform} stroke="var(--wave)" strokeWidth={Math.max(1, (duration / (peaks?.length ?? 1)) * PPS * 0.55)} strokeLinecap="round" />}
        </svg>
        <div className="playhead" />
      </div>
      <div className="transport">
        <button className="play" onClick={() => clock.toggle()} aria-label={playing ? 'Pause' : 'Play'}>
          {playing ? <svg width={14} height={16} viewBox="0 0 14 16"><path d="M1 1h4v14H1zM9 1h4v14H9z" fill="currentColor" /></svg>
            : <svg width={14} height={16} viewBox="0 0 14 16"><path d="M1 1l12 7-12 7z" fill="currentColor" /></svg>}
        </button>
        <span className="time" ref={time} />
        <label className="chip">Speed
          <select value={rate} onChange={(e) => clock.setRate(Number(e.target.value))} aria-label="Playback speed">
            {[0.5, 0.75, 1, 1.25].map((r) => <option key={r} value={r}>{r.toFixed(2).replace(/0$/, '')}×</option>)}
          </select>
        </label>
        <div className="legend">
          <span><i style={{ background: 'var(--tonic)' }} />Tonic</span>
          <span><i style={{ background: 'var(--subdominant)' }} />Subdominant</span>
          <span><i style={{ background: 'var(--dominant)' }} />Dominant</span>
          <span><i className="hatched" />Outside the key</span>
          <span>✦ Surprising change</span>
        </div>
      </div>
    </section>
  )
}

function ChordBlock({ chord, active, notation, surprising }: { chord: ChordSegment; active: boolean; notation: Notation; surprising: boolean }) {
  const x = chord.start * PPS + 1
  const w = Math.max(2, (chord.end - chord.start) * PPS - 2)
  const none = isNoChord(chord)
  const color = functionColor(chord.function)
  const fill = chord.function === 'borrowed' ? 'url(#hatch)' : none ? 'var(--surface-2)'
    : active ? color : `color-mix(in srgb, ${color} var(--tint), var(--surface))`
  const ink = active && !none ? '#fff' : 'var(--ink)'
  return (
    <g>
      <title>{`${chord.display} · ${chord.roman ?? ''} · ${(chord.end - chord.start).toFixed(1)} s · confidence ${Math.round(chord.confidence * 100)}%`}</title>
      <rect x={x} y={Y.rib} width={w} height={Y.ribH} rx={6} fill={fill} opacity={chord.confidence < 0.35 && !none ? 0.6 : 1} />
      <rect x={x} y={Y.rib + Y.ribH - 5} width={w} height={5} rx={2} fill={none ? 'var(--axis)' : color} />
      {w > 34 && <text x={x + 10} y={Y.rib + 26} fontSize={18} fontWeight={700} fill={ink}>{chordName(chord, notation)}</text>}
      {w > 60 && !none && (
        <text x={x + 10} y={Y.rib + 45} fontSize={12} fill={active ? '#fff' : 'var(--ink-2)'}>
          {notation === 'roman' ? chord.display : chord.roman}
        </text>
      )}
      {surprising && <text x={x + w - 18} y={Y.rib + 20} fontSize={13} fill={ink}>✦</text>}
    </g>
  )
}
