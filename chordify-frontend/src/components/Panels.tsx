import { useEffect, useMemo, useRef } from 'react'
import type { AnalysisResult, ChordSegment } from '../api/types'
import type { PlaybackClock } from '../lib/clock'
import { circleIndex, functionColor, isMinorQuality, isNoChord, MAJOR_CIRCLE, MINOR_CIRCLE, noteToPc, type Notation } from '../lib/music'

const C0 = 170, RO = 132, RI = 88

function position(index: number, radius: number): [number, number] {
  return [C0 + radius * Math.sin((index * Math.PI) / 6), C0 - radius * Math.cos((index * Math.PI) / 6)]
}

function nodeKey(chord: ChordSegment): string | null {
  if (!chord.root || isNoChord(chord)) return null
  return `${noteToPc(chord.root)}:${isMinorQuality(chord.quality) ? 'm' : 'M'}`
}

export function CircleOfFifths({ result, current }: { result: AnalysisResult; current: ChordSegment }) {
  const real = useMemo(() => result.chords.filter((c) => !isNoChord(c)), [result.chords])
  const nodes = useMemo(() => {
    const share = new Map(result.summary.time_share.map((s) => [s.display, s.share]))
    const map = new Map<string, { chord: ChordSegment; share: number; xy: [number, number]; label: string }>()
    for (const chord of real) {
      const key = nodeKey(chord)
      if (!key || map.has(key)) continue
      const minor = key.endsWith('m')
      const pc = Number(key.split(':')[0])
      const index = circleIndex(pc, minor)
      map.set(key, { chord, share: share.get(chord.display) ?? 0, xy: position(index, minor ? RI : RO),
                     label: minor ? MINOR_CIRCLE[index] : MAJOR_CIRCLE[index] })
    }
    return map
  }, [real, result.summary.time_share])
  const transitions = useMemo(() => {
    const counts = new Map<string, number>()
    real.forEach((c, i) => {
      if (i === 0) return
      const a = nodeKey(real[i - 1]), b = nodeKey(c)
      if (a && b && a !== b) counts.set(`${a}>${b}`, (counts.get(`${a}>${b}`) ?? 0) + 1)
    })
    return counts
  }, [real])
  const maxCount = Math.max(1, ...transitions.values())
  const tonic = noteToPc(result.key.global.tonic)
  const minorKey = result.key.global.mode === 'minor'
  const tonicIndex = circleIndex(tonic, minorKey)
  const wedge = (a0: number, a1: number, r0: number, r1: number) => {
    const p = (a: number, r: number) => [C0 + r * Math.sin(a), C0 - r * Math.cos(a)]
    const [x0, y0] = p(a0, r1), [x1, y1] = p(a1, r1), [x2, y2] = p(a1, r0), [x3, y3] = p(a0, r0)
    return `M${x0} ${y0}A${r1} ${r1} 0 0 1 ${x1} ${y1}L${x2} ${y2}A${r0} ${r0} 0 0 0 ${x3} ${y3}Z`
  }
  const currentNode = nodes.get(nodeKey(current) ?? '')
  const predicted = current.next?.[0]
  const predictedNode = predicted ? [...nodes.values()].find((n) => n.chord.display === predicted.display) : undefined
  const used = new Set([...nodes.values()].map((n) => n.label))
  return (
    <section className="card cof">
      <h2>Circle of fifths</h2>
      <svg viewBox="0 0 340 340" role="img" aria-label={`The song's chords on the circle of fifths, key of ${result.key.global.tonic} ${result.key.global.mode}`}>
        <path d={wedge(((tonicIndex - 1.5) * Math.PI) / 6, ((tonicIndex + 1.5) * Math.PI) / 6, 62, 158)} fill="var(--surface-2)" />
        <circle cx={C0} cy={C0} r={RO} fill="none" stroke="var(--grid)" />
        <circle cx={C0} cy={C0} r={RI} fill="none" stroke="var(--grid)" />
        {[...transitions.entries()].map(([k, n]) => {
          const [a, b] = k.split('>').map((key) => nodes.get(key)?.xy)
          if (!a || !b) return null
          const cx = C0 + ((a[0] + b[0]) / 2 - C0) * 0.25, cy = C0 + ((a[1] + b[1]) / 2 - C0) * 0.25
          return <path key={k} d={`M${a[0]} ${a[1]}Q${cx} ${cy} ${b[0]} ${b[1]}`} fill="none" stroke="var(--ink-2)"
                       strokeOpacity={0.35} strokeWidth={1 + (5 * n) / maxCount} strokeLinecap="round" />
        })}
        {currentNode && predictedNode && currentNode !== predictedNode && (
          <path d={`M${currentNode.xy[0]} ${currentNode.xy[1]}L${predictedNode.xy[0]} ${predictedNode.xy[1]}`} fill="none"
                stroke="var(--ink)" strokeWidth={2} strokeDasharray="4 4" />
        )}
        {MAJOR_CIRCLE.map((name, i) => !used.has(name) && (
          <text key={name} x={position(i, RO)[0]} y={position(i, RO)[1] + 4} textAnchor="middle" className="cof-label">{name}</text>
        ))}
        {MINOR_CIRCLE.map((name, i) => !used.has(name) && (
          <text key={name} x={position(i, RI)[0]} y={position(i, RI)[1] + 4} textAnchor="middle" className="cof-label small-label">{name}</text>
        ))}
        {[...nodes.values()].map((n) => {
          const r = 8 + 16 * Math.sqrt(n.share)
          return (
            <g key={n.label}>
              {n === currentNode && <circle cx={n.xy[0]} cy={n.xy[1]} r={r + 4} fill="none" stroke="var(--ink)" strokeWidth={2} />}
              <circle cx={n.xy[0]} cy={n.xy[1]} r={r} fill={functionColor(n.chord.function)} stroke="var(--surface)" strokeWidth={2} />
              <text x={n.xy[0]} y={n.xy[1] + 4} textAnchor="middle" fontSize={11} fontWeight={700} fill="#fff">{n.label}</text>
            </g>
          )
        })}
      </svg>
    </section>
  )
}

export function ChordShare({ result, chordsByDisplay }: { result: AnalysisResult; chordsByDisplay: Map<string, ChordSegment> }) {
  const top = result.summary.time_share[0]?.share ?? 1
  return (
    <section className="card">
      <h2>Time per chord</h2>
      {result.summary.time_share.slice(0, 10).map((s) => (
        <div className="share-row" key={s.display}>
          <b>{s.display}</b>
          <div className="bar" style={{ width: `${(s.share / top) * 100}%`, background: functionColor(chordsByDisplay.get(s.display)?.function) }} />
          <span className="pct">{Math.round(s.share * 100)}%</span>
        </div>
      ))}
      <div className="patterns">
        <div className="small">Repeating progressions</div>
        {result.summary.patterns.length === 0 && <div className="small">No loop repeats three times or more.</div>}
        {result.summary.patterns.map((p) => (
          <div className="pattern" key={p.roman.join('-')}>
            <span><b>{p.roman.join(' – ')}</b>{p.name && <><br /><span className="small">{p.name}</span></>}</span>
            <span className="time">×{p.count}</span>
          </div>
        ))}
        {result.summary.predictability != null && (
          <div className="small predictability">
            Predictability: the model gave the real next chord {Math.round(result.summary.predictability * 100)}% on average.
          </div>
        )}
      </div>
    </section>
  )
}

export function LeadSheet({ result, clock, notation, currentBar }: { result: AnalysisResult; clock: PlaybackClock; notation: Notation; currentBar: number }) {
  const sheet = useRef<HTMLDivElement>(null)
  const byDisplay = useMemo(() => new Map(result.chords.map((c) => [c.display, c])), [result.chords])
  useEffect(() => {
    const cell = sheet.current?.querySelector<HTMLElement>(`[data-bar="${currentBar}"]`)
    if (cell && sheet.current) sheet.current.scrollTop = cell.offsetTop - sheet.current.offsetTop - sheet.current.clientHeight / 2
  }, [currentBar])
  if (!result.bars.length) {
    return <section className="card"><h2>Lead sheet</h2><p className="small">No steady beat was found, so there is no bar grid.</p></section>
  }
  return (
    <section className="card">
      <h2>Lead sheet</h2>
      <div className="sheet" ref={sheet}>
        <div className="bars">
          {result.bars.map((bar, i) => (
            <div key={bar.start} data-bar={i} className={`barcell${i === currentBar ? ' cur' : ''}`} onClick={() => clock.seek(bar.start + 0.01)}>
              {bar.chords.map((d) => (notation === 'roman' ? byDisplay.get(d)?.roman ?? d : d)).join('  ') || '·'}
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}

const STAGES: Record<string, string> = {
  queued: 'Waiting for a free worker…', decode: 'Reading the audio…', beats: 'Finding the beat…',
  features: 'Listening for pitches…', acoustic: 'Recognising chords…', key: 'Working out the key…',
  decode_chords: 'Lining chords up with the beat…', analyse: 'Explaining the progression…', done: 'Done',
}

export function ProgressView({ stage, progress, upload }: { stage: string; progress: number; upload?: number }) {
  const label = upload !== undefined && upload < 1 ? `Uploading… ${Math.round(upload * 100)}%` : STAGES[stage] ?? 'Analysing…'
  const value = upload !== undefined && upload < 1 ? upload : progress
  return (
    <section className="card progress-card" aria-live="polite">
      <h2>Analysing</h2>
      <p className="stage">{label}</p>
      <div className="left big"><div style={{ width: `${Math.round(value * 100)}%` }} /></div>
    </section>
  )
}
