# Chordify web app

React 19 + TypeScript + Vite. Design and behaviour follow
[`docs/chord-progression-plan/06-frontend-visualization.md`](../docs/chord-progression-plan/06-frontend-visualization.md).

```bash
npm ci
npm run dev
```

`npm run dev` serves http://localhost:5173 and expects the API on http://localhost:8000.
`npm run build` typechecks and builds for production; `npm run lint` runs ESLint.

Set `VITE_API_BASE_URL` (see `.env.example`) when the API runs elsewhere.

## Screens

| Route | What |
|---|---|
| `#/` | Upload a file or record from the microphone (recorded as WAV in the browser, so the server needs no ffmpeg for recordings); recent analyses |
| `#/a/<id>` | Analysis: progress over Server-Sent Events, then Now playing / What comes next, timeline (chord blocks sized by duration, beat grid, waveform of the local file), circle of fifths, time per chord, repeating progressions, lead sheet |
| `#/quick` | One chord from a short clip (the original Chordify feature, via the v1 `/predict` endpoint) |
| `#/write` | Songwriter: type chords, get next-chord suggestions with reasons |

The uploaded audio stays in the browser for playback; the server keeps only the analysis.
Opening an analysis later asks for the file again and checks it by SHA-256.

## Code map

```
src/api/schema.d.ts   generated from the backend's OpenAPI document: npm run api:types
src/api/client.ts     REST + upload progress (XHR) + SSE (EventSource)
src/lib/clock.ts      one playback clock; React re-renders only when the chord/bar changes
src/lib/music.ts      function colours, guitar shapes, circle of fifths, binary search
src/lib/audio.ts      local file store, waveform peaks, AudioWorklet WAV recorder
src/components/       NowAndNext, Timeline, Panels (circle, share, lead sheet, progress), GuitarDiagram
src/pages/            Home, AnalysisPage, QuickChord, Songwriter
e2e/smoke.cjs         Playwright smoke test against running servers
```

Keyboard on the analysis screen: Space play/pause, ←/→ previous/next chord, R toggles Roman numerals.
