import { useState } from 'react'
import { ApiError, nextChords } from '../api/client'
import type { ProgressionResponse } from '../api/types'

const KEYS = ['', 'C', 'G', 'D', 'A', 'E', 'B', 'F#', 'Db', 'Ab', 'Eb', 'Bb', 'F', 'Am', 'Em', 'Bm', 'F#m', 'C#m', 'Dm', 'Gm', 'Cm']

/** Type a progression, get ranked continuations with reasons (POST /api/v2/progressions/next). */
export function Songwriter() {
  const [chords, setChords] = useState<string[]>(['C', 'G', 'Am'])
  const [draft, setDraft] = useState('')
  const [key, setKey] = useState('')
  const [answer, setAnswer] = useState<ProgressionResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  const ask = async (list: string[], keyName: string) => {
    setError(null)
    if (!list.length) {
      setAnswer(null)
      return
    }
    try {
      setAnswer(await nextChords({ chords: list, key: keyName || null, k: 5 }))
    } catch (err) {
      setAnswer(null)
      setError(err instanceof ApiError ? err.message : String(err))
    }
  }

  const update = (list: string[]) => {
    setChords(list)
    void ask(list, key)
  }

  const add = () => {
    const names = draft.split(/[\s,|]+/).filter(Boolean)
    if (names.length) update([...chords, ...names])
    setDraft('')
  }

  return (
    <div className="grid-top">
      <section className="card">
        <h2>Songwriter</h2>
        <p>Type chords as you would write them (C, Am7, F/C, G7sus4) and see what usually follows.</p>
        <div className="progression">
          {chords.map((c, i) => (
            <button key={`${c}-${i}`} className="chip removable" onClick={() => update(chords.filter((_, j) => j !== i))}
                    aria-label={`Remove ${c}`}>{c} ×</button>
          ))}
        </div>
        <form className="row" onSubmit={(e) => { e.preventDefault(); add() }}>
          <input value={draft} onChange={(e) => setDraft(e.target.value)} placeholder="Add chords, e.g. F G" aria-label="Chords to add" />
          <button type="submit">Add</button>
        </form>
        <label className="row">Key
          <select value={key} onChange={(e) => { setKey(e.target.value); void ask(chords, e.target.value) }}>
            {KEYS.map((k) => <option key={k} value={k}>{k || 'Detect from the chords'}</option>)}
          </select>
        </label>
        <button onClick={() => void ask(chords, key)}>Suggest the next chord</button>
        {error && <div className="notice warn" role="alert">{error}</div>}
      </section>
      <section className="card" aria-live="polite">
        <h2>What could come next</h2>
        {!answer && <p className="small">Suggestions appear here.</p>}
        {answer && (
          <>
            <p className="small">{answer.key_used.estimated ? 'Assuming' : 'In'} {answer.key_used.tonic} {answer.key_used.mode}
              {answer.key_used.estimated && ` (${Math.round(answer.key_used.confidence * 100)}% sure)`}</p>
            <div className="next-list">
              {answer.predictions.map((p) => (
                <div className="pred" key={p.label}>
                  <button className="name linklike" onClick={() => update([...chords, p.display])} title="Add to the progression">{p.display}</button>
                  <span className="rn">{p.roman}</span>
                  <div className="bar"><div style={{ width: `${p.p * 100}%`, background: 'var(--tonic)' }} /></div>
                  <span className="pct">{Math.round(p.p * 100)}%</span>
                  <span className="why">{p.reason}</span>
                </div>
              ))}
            </div>
          </>
        )}
      </section>
    </div>
  )
}
