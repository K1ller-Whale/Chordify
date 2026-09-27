import type { AnalysisResult, ChordSegment } from '../api/types'
import { hasOpenShape, noteToPc } from './music'

// With a capo on fret N, every chord is played with the shape of the chord N semitones
// lower: a song in Ab with the capo on 1 is played with G shapes. The app shows those shapes;
// Roman numerals and functions are relative to the key, so they do not change.

export const MAX_CAPO = 9

const SHARP_NAMES = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B']
const FLAT_NAMES = ['C', 'Db', 'D', 'Eb', 'E', 'F', 'Gb', 'G', 'Ab', 'A', 'Bb', 'B']
// The same key signatures as the server (chordify_core/theory.py): F# major, not Gb
const FLAT_MAJOR = new Set([5, 10, 3, 8, 1])
const FLAT_MINOR = new Set([2, 7, 0, 5, 10, 3])

const NOTE = /^([A-G][#b]*)/
const DISPLAY = /^([A-G][#b]*)(.*?)(?:\/([A-G][#b]*))?$/

const pcName = (pc: number, flats: boolean) => (flats ? FLAT_NAMES : SHARP_NAMES)[((pc % 12) + 12) % 12]

export interface ShapeKey {
  tonic: string
  mode: string
  flats: boolean
}

/** The key the shapes are in: the song's key moved down by the capo, spelled as usual. */
export function shapeKey(key: { tonic: string; mode: string }, capo: number): ShapeKey {
  const pc = (((noteToPc(key.tonic) - capo) % 12) + 12) % 12
  const flats = (key.mode === 'minor' ? FLAT_MINOR : FLAT_MAJOR).has(pc)
  return { tonic: pcName(pc, flats), mode: key.mode, flats }
}

export function transposeNote(name: string, semitones: number, flats: boolean): string {
  return pcName(noteToPc(name) + semitones, flats)
}

/** 'Cmaj7/E' -> 'Bmaj7/D#' for -1; the suffix is kept, 'N.C.' is left alone. */
export function transposeDisplay(display: string, semitones: number, flats: boolean): string {
  const match = DISPLAY.exec(display)
  if (!match) return display
  const [, root, suffix, bass] = match
  return transposeNote(root, semitones, flats) + suffix + (bass ? `/${transposeNote(bass, semitones, flats)}` : '')
}

/** Harte labels: 'C:maj7/3' -> 'B:maj7/3' (the bass is an interval, so it stays). */
export function transposeLabel(label: string, semitones: number, flats: boolean): string {
  const match = NOTE.exec(label)
  return match ? transposeNote(match[1], semitones, flats) + label.slice(match[1].length) : label
}

function transposeChord(chord: ChordSegment, shift: number, flats: boolean): ChordSegment {
  return {
    ...chord,
    label: transposeLabel(chord.label, shift, flats),
    display: transposeDisplay(chord.display, shift, flats),
    root: chord.root ? transposeNote(chord.root, shift, flats) : chord.root,
    bass: chord.bass ? transposeNote(chord.bass, shift, flats) : chord.bass,
    // 'Ab Mixolydian' -> 'G Mixolydian': the leading note moves like a label's root
    scale_hint: chord.scale_hint ? transposeLabel(chord.scale_hint, shift, flats) : chord.scale_hint,
    alternatives: chord.alternatives.map((a) => ({
      ...a, label: transposeLabel(a.label, shift, flats), display: transposeDisplay(a.display, shift, flats),
    })),
    next: chord.next.map((p) => ({
      ...p, label: transposeLabel(p.label, shift, flats), display: transposeDisplay(p.display, shift, flats),
    })),
  }
}

/** The analysis as it looks with the capo on fret ``capo``: every chord name is the shape to play. */
export function withCapo(result: AnalysisResult, capo: number): AnalysisResult {
  if (!capo) return result
  const key = shapeKey(result.key.global, capo)
  const shift = -capo
  const display = (d: string) => transposeDisplay(d, shift, key.flats)
  return {
    ...result,
    key: {
      ...result.key,
      global: { ...result.key.global, tonic: key.tonic },
      segments: result.key.segments.map((s) => ({ ...s, tonic: transposeNote(s.tonic, shift, key.flats) })),
    },
    chords: result.chords.map((c) => transposeChord(c, shift, key.flats)),
    bars: result.bars.map((b) => ({ ...b, chords: b.chords.map(display) })),
    summary: { ...result.summary, time_share: result.summary.time_share.map((s) => ({ ...s, display: display(s.display) })) },
  }
}

export interface CapoSuggestion {
  capo: number
  open: number // share of the chord time playable with open shapes
}

/** Share of the chord time (N.C. excluded) that has an open-position shape with the capo on ``capo``. */
export function openShare(chords: ChordSegment[], capo: number): number {
  let open = 0
  let total = 0
  for (const chord of chords) {
    if (!chord.root || !chord.quality) continue
    const length = chord.end - chord.start
    total += length
    if (hasOpenShape((noteToPc(chord.root) - capo + 12) % 12, chord.quality)) open += length
  }
  return total ? open / total : 0
}

/** The capo position (0–7) whose shapes are most often open chords, the lowest on a tie.
 *  No capo unless one gives at least 10 % more of the song in open chords. */
export function suggestCapo(chords: ChordSegment[]): CapoSuggestion {
  const none: CapoSuggestion = { capo: 0, open: openShare(chords, 0) }
  let best = none
  for (let capo = 1; capo <= 7; capo++) {
    const open = openShare(chords, capo)
    if (open > best.open + 1e-9) best = { capo, open }
  }
  return best.open >= none.open + 0.1 ? best : none
}
