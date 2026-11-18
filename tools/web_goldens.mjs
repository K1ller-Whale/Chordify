// Golden values for the Android app's port of the web app's music and capo logic
// (chordify-frontend/src/lib/music.ts and capo.ts), computed by those very files, so the app
// shows the same guitar shapes, capo suggestions and transposed names as the web app.
//
//   node --experimental-strip-types tools/web_goldens.mjs           # writes the golden file
//   node --experimental-strip-types tools/web_goldens.mjs --check   # fails if it is stale
//
// The analysis results it transposes come from the engine's golden file (analysis.json.gz).
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { gunzipSync } from 'node:zlib'

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..')
const LIB = join(REPO, 'chordify-frontend', 'src', 'lib')
const ANALYSIS = join(REPO, 'Mobile', 'engine', 'src', 'test', 'resources', 'goldens', 'analysis.json.gz')
const OUT = join(REPO, 'Mobile', 'app', 'src', 'test', 'resources', 'goldens', 'web.json')

// Node runs .ts files with their types stripped, but only with explicit .ts import paths.
const work = mkdtempSync(join(tmpdir(), 'chordify-web-'))
for (const name of ['music.ts', 'capo.ts']) {
  const text = readFileSync(join(LIB, name), 'utf8').replace(/from '\.\/music'/g, "from './music.ts'")
  writeFileSync(join(work, name), text)
}
const music = await import(pathToFileURL(join(work, 'music.ts')).href)
const capo = await import(pathToFileURL(join(work, 'capo.ts')).href)
rmSync(work, { recursive: true, force: true })

const SHARPS = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B']
const FLATS = ['C', 'Db', 'D', 'Eb', 'E', 'F', 'Gb', 'G', 'Ab', 'A', 'Bb', 'B']
// the large vocabulary's chord types (Mobile/engine/.../vocab/Vocabulary.kt LARGE_QUALITIES)
const QUALITIES = ['maj', 'min', 'dim', 'aug', 'maj6', 'min6', '7', 'maj7', 'min7', 'dim7', 'hdim7', 'minmaj7', 'sus2', 'sus4']
const RESULTS = ['loop.wav', 'sevenths_stereo_22k.wav', 'waltz.wav']

const analysis = JSON.parse(gunzipSync(readFileSync(ANALYSIS)).toString('utf8'))
const results = RESULTS.map((file) => analysis.inputs.find((input) => input.file === file).result)

const shapes = []
for (let pc = 0; pc < 12; pc++) {
  for (const quality of QUALITIES) {
    const chord = { label: `${SHARPS[pc]}:${quality}`, root: SHARPS[pc], quality }
    shapes.push({ root: SHARPS[pc], quality, shape: music.guitarShape(chord), open: music.hasOpenShape(pc, quality),
                  minor: music.isMinorQuality(quality) })
  }
}

const keys = []
for (const mode of ['major', 'minor']) {
  for (let pc = 0; pc < 12; pc++) {
    for (const tonic of new Set([SHARPS[pc], FLATS[pc]])) {
      for (let fret = 0; fret <= capo.MAX_CAPO; fret++) keys.push({ tonic, mode, capo: fret, shapes: capo.shapeKey({ tonic, mode }, fret) })
    }
  }
}

const DISPLAYS = ['C', 'Cmaj7/E', 'F#m', 'Bbm7b5', 'Ab7', 'G/B', 'Ebmaj7', 'C#dim7', 'N.C.', 'Dsus4', 'E+', 'Abm6']
const LABELS = ['C:maj7/3', 'F#:min', 'Bb:hdim7', 'N', 'X', 'Ab:7', 'E:aug', 'Db:sus2(b7)']
const transposed = []
for (const shift of [-9, -5, -3, -2, -1, 0, 1, 7]) {
  for (const flats of [false, true]) {
    for (const display of DISPLAYS) transposed.push({ kind: 'display', value: display, shift, flats, out: capo.transposeDisplay(display, shift, flats) })
    for (const label of LABELS) transposed.push({ kind: 'label', value: label, shift, flats, out: capo.transposeLabel(label, shift, flats) })
  }
}

const starts = [0, 1.5, 3, 4.25, 8]
const golden = {
  sources: ['chordify-frontend/src/lib/music.ts', 'chordify-frontend/src/lib/capo.ts'],
  max_capo: capo.MAX_CAPO,
  notes: ['C', 'C#', 'Db', 'B#', 'Cb', 'Fb', 'E#', 'Bbb', 'F##', 'G', 'Ab', 'A#'].map((name) => ({ name, pc: music.noteToPc(name) })),
  shapes,
  keys,
  transposed,
  circle: [false, true].flatMap((minor) => Array.from({ length: 12 }, (_, pc) => ({ pc, minor, index: music.circleIndex(pc, minor) }))),
  times: [0, 7.9, 59.99, 60, 61.5, 899.4, -3].map((seconds) => ({ seconds, text: music.formatTime(seconds) })),
  chord_at: { starts, cases: [-1, 0, 0.5, 1.5, 2.99, 3, 4.3, 8, 100].map((t) => ({ t, index: music.chordIndexAt(starts, t) })) },
  results: results.map((result) => ({
    file: result.source.filename,
    result,
    suggestion: capo.suggestCapo(result.chords),
    open_share: Array.from({ length: 8 }, (_, fret) => capo.openShare(result.chords, fret)),
    names: result.chords.map((chord) => ({ letters: music.chordName(chord, 'letters'), roman: music.chordName(chord, 'roman') })),
    with_capo: [1, 3].map((fret) => ({ capo: fret, result: capo.withCapo(result, fret) })),
  })),
}

const text = JSON.stringify(golden) + '\n'
if (process.argv.includes('--check')) {
  // a Windows checkout with core.autocrlf may have turned the final newline into CRLF
  if (readFileSync(OUT, 'utf8').replaceAll('\r\n', '\n') !== text) {
    console.error(`${OUT} is stale: run node --experimental-strip-types tools/web_goldens.mjs and commit it`)
    process.exit(1)
  }
  console.log('web.json is up to date')
} else {
  mkdirSync(dirname(OUT), { recursive: true })
  writeFileSync(OUT, text)
  console.log(`web.json: ${Math.round(text.length / 1024)} KB`)
}
