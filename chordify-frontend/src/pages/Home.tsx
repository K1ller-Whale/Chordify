import { useEffect, useRef, useState } from 'react'
import { ApiError, createAnalysis, listAnalyses } from '../api/client'
import type { AnalysisStatus } from '../api/types'
import { ProgressView } from '../components/Panels'
import { rememberFile, WavRecorder } from '../lib/audio'
import { formatTime } from '../lib/music'

const SIMPLE_KEY = 'chordify.simpleChords'

function readSimple(): boolean {
  try {
    return window.localStorage.getItem(SIMPLE_KEY) === '1'
  } catch {
    return false
  }
}

export function Home({ navigate }: { navigate: (hash: string) => void }) {
  const [upload, setUpload] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [recent, setRecent] = useState<AnalysisStatus[]>([])
  const [dragging, setDragging] = useState(false)
  const [recording, setRecording] = useState(false)
  const [elapsed, setElapsed] = useState(0)
  const [simple, setSimple] = useState(readSimple)
  const recorder = useRef<WavRecorder | null>(null)

  useEffect(() => {
    listAnalyses().then(setRecent).catch(() => setRecent([]))
  }, [])

  useEffect(() => {
    if (!recording) return
    const timer = window.setInterval(() => setElapsed(recorder.current?.seconds ?? 0), 250)
    return () => window.clearInterval(timer)
  }, [recording])

  const analyse = async (file: File) => {
    setError(null)
    setUpload(0)
    try {
      const status = await createAnalysis(file, file.name, setUpload, simple ? { vocabulary: 'majmin' } : undefined)
      rememberFile(status.id, file)
      navigate(`#/a/${status.id}`)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : String(err))
      setUpload(null)
    }
  }

  const toggleSimple = (on: boolean) => {
    setSimple(on)
    try {
      window.localStorage.setItem(SIMPLE_KEY, on ? '1' : '0')
    } catch {
      // private mode: the choice lasts until the page is closed
    }
  }

  const toggleRecording = async () => {
    if (!recording) {
      try {
        recorder.current = new WavRecorder()
        await recorder.current.start()
        setElapsed(0)
        setRecording(true)
      } catch {
        setError('Microphone access was refused or is unavailable.')
      }
      return
    }
    setRecording(false)
    const blob = await recorder.current!.stop()
    await analyse(new File([blob], `recording-${new Date().toISOString().slice(0, 19)}.wav`, { type: 'audio/wav' }))
  }

  if (upload !== null) return <ProgressView stage="queued" progress={0} upload={upload} />

  return (
    <>
      <section className="hero">
        <h1>See every chord in a song</h1>
        <p>Upload a recording or play into the microphone. Chordify finds the chords, the beat and the key, and
          shows what usually comes next and why.</p>
      </section>
      <div className="grid-top">
        <label className={`card dropzone${dragging ? ' dragging' : ''}`}
               onDragOver={(e) => { e.preventDefault(); setDragging(true) }}
               onDragLeave={() => setDragging(false)}
               onDrop={(e) => { e.preventDefault(); setDragging(false); const f = e.dataTransfer.files[0]; if (f) void analyse(f) }}>
          <h2>Analyse a song</h2>
          <p className="big-text">Drop an audio file here, or <span className="link">choose one</span></p>
          <p className="small">wav, mp3, flac, ogg, m4a · up to 15 minutes · the audio is not kept on the server</p>
          <input type="file" accept="audio/*" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) void analyse(f) }} />
        </label>
        <section className="card">
          <h2>Record</h2>
          <p>Play a progression on your instrument, then stop to analyse it.</p>
          <button className={`record${recording ? ' on' : ''}`} onClick={() => void toggleRecording()}>
            {recording ? `Stop and analyse (${formatTime(elapsed)})` : 'Start recording'}
          </button>
          <p className="small">Single chord? Use <a href="#/quick">Quick chord</a>. Writing a song? Try the <a href="#/write">Songwriter</a>.</p>
        </section>
      </div>
      <label className="option">
        <input type="checkbox" checked={simple} onChange={(e) => toggleSimple(e.target.checked)} />
        Major and minor chords only (C instead of Cmaj7, G instead of G7)
      </label>
      {error && <div className="notice warn" role="alert">{error}</div>}
      {recent.length > 0 && (
        <section className="card">
          <h2>Recent analyses</h2>
          <ul className="recent">
            {recent.slice(0, 10).map((a) => (
              <li key={a.id}>
                <a href={`#/a/${a.id}`}>{a.filename ?? a.id}</a>
                <span className="small">{a.status === 'completed' ? a.created_at.replace('T', ' ').slice(0, 16) : a.status}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  )
}
