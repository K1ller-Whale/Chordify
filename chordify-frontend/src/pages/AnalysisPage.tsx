import { useEffect, useMemo, useRef, useState } from 'react'
import { ApiError, exportUrl, getResult, getStatus, subscribeToAnalysis } from '../api/client'
import type { AnalysisResult, ChordSegment } from '../api/types'
import { ChordShare, CircleOfFifths, LeadSheet, ProgressView } from '../components/Panels'
import { NextChord, NowPlaying } from '../components/NowAndNext'
import { Timeline } from '../components/Timeline'
import { recallFile, rememberFile, sha256, waveformPeaks } from '../lib/audio'
import { MAX_CAPO, readCapo, saveCapo, shapeKey, suggestCapo, withCapo } from '../lib/capo'
import { PlaybackClock, useClock } from '../lib/clock'
import { chordIndexAt, isNoChord, type Notation } from '../lib/music'

type Phase =
  | { kind: 'loading' }
  | { kind: 'running'; stage: string; progress: number }
  | { kind: 'failed'; code: string; message: string }
  | { kind: 'ready'; result: AnalysisResult }

export function AnalysisPage({ id }: { id: string }) {
  const [phase, setPhase] = useState<Phase>({ kind: 'loading' })

  useEffect(() => {
    let cancelled = false
    let close = () => {}
    const load = () => getResult(id).then((result) => !cancelled && setPhase({ kind: 'ready', result }))
    getStatus(id).then((status) => {
      if (cancelled) return
      if (status.status === 'completed') return load()
      if (status.status === 'failed') {
        setPhase({ kind: 'failed', code: status.error?.code ?? 'FAILED', message: status.error?.message ?? 'The analysis failed.' })
        return
      }
      setPhase({ kind: 'running', stage: status.stage ?? 'queued', progress: status.progress })
      close = subscribeToAnalysis(id, {
        onProgress: (e) => !cancelled && setPhase({ kind: 'running', stage: e.stage, progress: e.progress }),
        onCompleted: () => void load(),
        onFailed: (e) => !cancelled && setPhase({ kind: 'failed', code: e.code, message: e.message }),
      })
    }).catch((err: unknown) => {
      if (cancelled) return
      const e = err instanceof ApiError ? err : new ApiError(0, 'ERROR', String(err))
      setPhase({ kind: 'failed', code: e.code, message: e.message })
    })
    return () => {
      cancelled = true
      close()
    }
  }, [id])

  if (phase.kind === 'loading') return <ProgressView stage="queued" progress={0} />
  if (phase.kind === 'running') return <ProgressView stage={phase.stage} progress={phase.progress} />
  if (phase.kind === 'failed') {
    return (
      <section className="card error-card" role="alert">
        <h2>Could not analyse this audio</h2>
        <p>{phase.message}</p>
        <p className="small">Error code: {phase.code}</p>
        <a className="button" href="#/">Try another file</a>
      </section>
    )
  }
  return <AnalysisView id={id} result={phase.result} />
}

function AnalysisView({ id, result: analysed }: { id: string; result: AnalysisResult }) {
  const [clock] = useState(() => {
    const c = new PlaybackClock()
    c.duration = analysed.source.duration
    return c
  })
  const [notation, setNotation] = useState<Notation>('letters')
  const [capo, setCapo] = useState(() => readCapo(id))
  // Everything below shows the shapes to play with the capo on; the header keeps the real key.
  const result = useMemo(() => withCapo(analysed, capo), [analysed, capo])
  const suggestion = useMemo(() => suggestCapo(analysed.chords), [analysed.chords])
  const [file, setFile] = useState<File | undefined>(() => recallFile(id))
  const [peaks, setPeaks] = useState<number[] | null>(null)
  const [mismatch, setMismatch] = useState(false)
  const audio = useRef<HTMLAudioElement>(null)

  // The object URL is created and revoked by the same effect (StrictMode-safe) and set on
  // the element directly, so the clock always follows a live source.
  useEffect(() => {
    const element = audio.current
    if (!file || !element) return
    const url = URL.createObjectURL(file)
    element.src = url
    clock.attach(element)
    return () => {
      clock.attach(null)
      element.removeAttribute('src')
      URL.revokeObjectURL(url)
    }
  }, [clock, file])

  useEffect(() => {
    if (!file) return
    let active = true
    waveformPeaks(file, Math.max(0.05, result.source.duration / 3000)).then((p) => active && setPeaks(p)).catch(() => {})
    return () => {
      active = false
    }
  }, [file, result.source.duration])

  useEffect(() => () => clock.dispose(), [clock])

  const starts = useMemo(() => result.chords.map((c) => c.start), [result.chords])
  const barStarts = useMemo(() => result.bars.map((b) => b.start), [result.bars])
  const index = useClock(clock, (c) => chordIndexAt(starts, c.time))
  const barIndex = useClock(clock, (c) => (barStarts.length && c.time >= barStarts[0] ? chordIndexAt(barStarts, c.time) : -1))
  const chord = result.chords[index]
  const real = useMemo(() => result.chords.filter((c) => !isNoChord(c)), [result.chords])
  const chordsByDisplay = useMemo(() => new Map(real.map((c) => [c.display, c])), [real])
  const actualNext = real[real.findIndex((c) => c.index === chord.index) + 1] as ChordSegment | undefined
  const surprising = useMemo(() => new Set(real.filter((c) => (c.surprise ?? 0) > Math.log2(1 / 0.15)).map((c) => c.index)), [real])

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.target as HTMLElement).tagName === 'INPUT' || (event.target as HTMLElement).tagName === 'SELECT') return
      if (event.code === 'Space') {
        event.preventDefault()
        clock.toggle()
      } else if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
        const i = chordIndexAt(starts, clock.time) + (event.key === 'ArrowRight' ? 1 : -1)
        clock.seek(starts[Math.max(0, Math.min(starts.length - 1, i))] + 0.01)
      } else if (event.key === 'r') {
        setNotation((n) => (n === 'roman' ? 'letters' : 'roman'))
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [clock, starts])

  const changeCapo = (value: number) => {
    setCapo(value)
    saveCapo(id, value)
  }

  const attach = async (picked: File | undefined) => {
    if (!picked) return
    setMismatch(!!result.source.sha256 && (await sha256(picked)) !== result.source.sha256)
    rememberFile(id, picked)
    setFile(picked)
  }

  const key = analysed.key.global
  const shapes = shapeKey(key, capo)
  const mode = key.mode === 'unknown' ? '' : ` ${key.mode}`
  return (
    <>
      <header className="song-header">
        <div className="song">
          <b>{result.source.title ?? result.source.filename ?? 'Untitled'}</b>
          {result.source.artist && <span>{result.source.artist}</span>}
        </div>
        <div className="chips">
          <span className="chip">Key <b>{key.tonic} {key.mode}</b></span>
          {result.tempo.bpm && <span className="chip"><b>{Math.round(result.tempo.bpm)}</b> BPM</span>}
          <span className="chip"><b>{result.tempo.meter}</b></span>
          <span className="chip"><b>{result.summary.unique_chords}</b> chords</span>
          <a className="chip" href={exportUrl(id, 'lab')} download>Export .lab</a>
        </div>
        <label className="chip">
          Capo
          <select value={capo} onChange={(e) => changeCapo(Number(e.target.value))} aria-label="Capo position">
            <option value={0}>none</option>
            {Array.from({ length: MAX_CAPO }, (_, i) => i + 1).map((fret) => (
              <option key={fret} value={fret}>fret {fret}</option>
            ))}
          </select>
        </label>
        <div className="seg" role="group" aria-label="Chord notation">
          <button aria-pressed={notation === 'letters'} onClick={() => setNotation('letters')}>Letters</button>
          <button aria-pressed={notation === 'roman'} onClick={() => setNotation('roman')}>Roman</button>
        </div>
      </header>
      {(capo > 0 || suggestion.capo > 0) && (
        <div className="notice">
          {capo > 0 && (
            <span>Capo on fret {capo}: chords are shown as the shapes you play, in <b>{shapes.tonic}{mode}</b>.
              The song sounds in {key.tonic}{mode}. </span>
          )}
          {suggestion.capo > 0 && suggestion.capo !== capo && (
            <span>With the capo on fret {suggestion.capo}, {Math.round(suggestion.open * 100)}% of the song is open chords.{' '}
              <button className="linklike link" onClick={() => changeCapo(suggestion.capo)}>Use capo {suggestion.capo}</button></span>
          )}
        </div>
      )}
      {!file && (
        <div className="notice">
          The audio is not stored on the server. <label className="link">Attach the file you analysed
            <input type="file" accept="audio/*" onChange={(e) => void attach(e.target.files?.[0])} hidden /></label> to play along;
          until then the timeline runs silently.
        </div>
      )}
      {mismatch && <div className="notice warn">This file is not the one that was analysed, so chords may not line up.</div>}
      {file && <audio ref={audio} preload="auto" />}
      <div className="grid-top">
        <NowPlaying chord={chord} result={result} clock={clock} notation={notation} capo={capo}
                    sounding={analysed.chords[index]?.display} />
        <NextChord chord={chord} actualNext={actualNext} chordsByDisplay={chordsByDisplay} notation={notation} />
      </div>
      <Timeline result={result} clock={clock} current={index} notation={notation} peaks={peaks} surprising={surprising} />
      <div className="grid-bottom">
        <CircleOfFifths result={result} current={chord} />
        <ChordShare result={result} chordsByDisplay={chordsByDisplay} />
        <LeadSheet result={result} clock={clock} notation={notation} currentBar={barIndex} />
      </div>
      <p className="small footnote">Models: {Object.entries(result.models).map(([k, v]) => `${k} ${v}`).join(' · ')}.
        Space plays/pauses, ←/→ jump between chords, R toggles Roman numerals.</p>
    </>
  )
}
