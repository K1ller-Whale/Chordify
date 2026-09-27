import { useRef, useState } from 'react'
import { ApiError, predictSingleChord } from '../api/client'
import type { LegacyChordPrediction } from '../api/types'
import { GuitarDiagram } from '../components/GuitarDiagram'
import { WavRecorder } from '../lib/audio'
import { guitarShape } from '../lib/music'

/** One chord from a short clip (the original Chordify feature, now on the v2 pipeline). */
export function QuickChord() {
  const [state, setState] = useState<'idle' | 'recording' | 'working'>('idle')
  const [result, setResult] = useState<LegacyChordPrediction | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [level, setLevel] = useState(0)
  const recorder = useRef<WavRecorder | null>(null)

  const send = async (blob: Blob, name: string) => {
    setState('working')
    setError(null)
    try {
      setResult(await predictSingleChord(blob, name))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : String(err))
    } finally {
      setState('idle')
    }
  }

  const toggle = async () => {
    if (state === 'recording') {
      const blob = await recorder.current!.stop()
      await send(blob, 'chord.wav')
      return
    }
    try {
      recorder.current = new WavRecorder()
      recorder.current.onLevel = setLevel
      await recorder.current.start()
      setResult(null)
      setState('recording')
    } catch {
      setError('Microphone access was refused or is unavailable.')
    }
  }

  const [root, quality] = result?.chord.includes(':') ? result.chord.split(':') : [null, null]
  const display = root ? `${root}${quality === 'min' ? 'm' : ''}` : result?.chord === 'N' ? 'No chord' : result?.chord
  return (
    <div className="grid-top">
      <section className="card">
        <h2>Quick chord</h2>
        <p>Strum one chord for two or three seconds, then stop.</p>
        <button className={`record${state === 'recording' ? ' on' : ''}`} disabled={state === 'working'} onClick={() => void toggle()}>
          {state === 'recording' ? 'Stop' : state === 'working' ? 'Listening…' : 'Record a chord'}
        </button>
        {state === 'recording' && <div className="left"><div style={{ width: `${Math.min(100, level * 400)}%` }} /></div>}
        <p className="small">Or <label className="link">upload a short clip
          <input type="file" accept="audio/*" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) void send(f, f.name) }} />
        </label>.</p>
        {error && <div className="notice warn" role="alert">{error}</div>}
      </section>
      <section className="card" aria-live="polite">
        <h2>Result</h2>
        {result ? (
          <div className="now">
            <div className="now-meta">
              <div className="now-name">{display}</div>
              <div className="small">Confidence {Math.round(result.confidence)}%</div>
            </div>
            {root && <GuitarDiagram shape={guitarShape({ label: result.chord, root, quality })} />}
          </div>
        ) : <p className="small">Your chord will appear here.</p>}
      </section>
    </div>
  )
}
