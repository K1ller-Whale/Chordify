import { useEffect, useRef } from 'react'
import type { AnalysisResult, ChordSegment } from '../api/types'
import type { PlaybackClock } from '../lib/clock'
import { chordName, FUNCTION_NAMES, functionColor, guitarShape, isNoChord, type Notation } from '../lib/music'
import { GuitarDiagram } from './GuitarDiagram'

interface NowProps {
  chord: ChordSegment
  result: AnalysisResult
  clock: PlaybackClock
  notation: Notation
}

export function NowPlaying({ chord, result, clock, notation }: NowProps) {
  const bar = useRef<HTMLDivElement>(null)
  const countdown = useRef<HTMLDivElement>(null)
  const key = result.key.global

  useEffect(() => {
    const beats = result.beats
    const update = () => {
      const left = Math.max(0, chord.end - clock.time)
      if (bar.current) bar.current.style.width = `${(1 - left / Math.max(1e-6, chord.end - chord.start)) * 100}%`
      if (countdown.current) {
        const beatsLeft = beats.filter((b) => b > clock.time && b < chord.end - 0.05).length
        countdown.current.textContent = isNoChord(chord) ? ''
          : `${beatsLeft} beat${beatsLeft === 1 ? '' : 's'} until the change (${left.toFixed(1)} s)`
      }
    }
    update()
    return clock.subscribe(update)
  }, [chord, clock, result.beats])

  const fn = chord.function as keyof typeof FUNCTION_NAMES | null | undefined
  return (
    <section className="card" aria-live="polite">
      <h2>Now playing</h2>
      <div className="now">
        <div className="now-meta">
          <div className="now-name">{chordName(chord, notation)}</div>
          {isNoChord(chord) ? <div className="fn">No chord</div> : (
            <div className="fn">
              <span className="dot" style={{ background: functionColor(fn) }} />
              <b>{notation === 'roman' ? chord.display : chord.roman}</b>
              {fn && ` · ${FUNCTION_NAMES[fn]} in ${key.tonic} ${key.mode}`}
            </div>
          )}
          {chord.scale_hint && <div className="small">Scale to play over it: {chord.scale_hint}</div>}
          <div className="left"><div ref={bar} /></div>
          <div className="small" ref={countdown} />
          {chord.confidence < 0.5 && !isNoChord(chord) && (
            <div className="small">Unsure: {chord.alternatives?.slice(0, 2).map((a) => a.display).join(' or ')} also possible</div>
          )}
        </div>
        {!isNoChord(chord) && <GuitarDiagram shape={guitarShape(chord)} />}
      </div>
    </section>
  )
}

interface NextProps {
  chord: ChordSegment
  actualNext: ChordSegment | undefined
  chordsByDisplay: Map<string, ChordSegment>
  notation: Notation
}

export function NextChord({ chord, actualNext, chordsByDisplay, notation }: NextProps) {
  const predictions = chord.next ?? []
  const rank = actualNext ? predictions.findIndex((p) => p.roman === actualNext.roman) : -1
  return (
    <section className="card">
      <h2>What comes next</h2>
      <div className="next-list">
        {predictions.length === 0 && <span className="small">Predictions appear once a chord is playing.</span>}
        {predictions.map((p, k) => (
          <div className="pred" key={p.label}>
            <span className="name">{notation === 'roman' ? p.roman : p.display}</span>
            <span className="rn">{notation === 'roman' ? p.display : p.roman}</span>
            <div className="bar">
              <div style={{ width: `${p.p * 100}%`, background: functionColor(chordsByDisplay.get(p.display)?.function) }} />
            </div>
            <span className="pct">{Math.round(p.p * 100)}%</span>
            <span className="why">{k === 0 ? 'Most likely · ' : ''}{p.reason}</span>
          </div>
        ))}
      </div>
      <div className="next-foot">
        <span>
          {actualNext && predictions.length > 0 && (
            <>Actual next: <b>{chordName(actualNext, notation)}</b> {rank >= 0 ? `✓ predicted #${rank + 1}` : '✦ not in the top 3'}</>
          )}
        </span>
        {chord.theory_only && chord.theory_only.length > 0 && (
          <span>Without this song’s history: {chord.theory_only.map((p) => `${p.roman} ${Math.round(p.p * 100)}%`).join(' · ')}</span>
        )}
      </div>
    </section>
  )
}
