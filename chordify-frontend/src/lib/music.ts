import type { ChordSegment, HarmonicFunction } from '../api/types'

const NATURALS: Record<string, number> = { C: 0, D: 2, E: 4, F: 5, G: 7, A: 9, B: 11 }

export function noteToPc(name: string): number {
  let pc = NATURALS[name[0]] ?? 0
  for (const accidental of name.slice(1)) pc += accidental === '#' ? 1 : accidental === 'b' ? -1 : 0
  return ((pc % 12) + 12) % 12
}

export const isNoChord = (chord: Pick<ChordSegment, 'label'>) => chord.label === 'N' || chord.label === 'X'

export type Notation = 'letters' | 'roman'

export const chordName = (chord: ChordSegment, notation: Notation) =>
  isNoChord(chord) ? 'N.C.' : notation === 'roman' ? chord.roman ?? chord.display : chord.display

export function functionColor(fn: string | null | undefined): string {
  switch (fn as HarmonicFunction | undefined) {
    case 'tonic':
      return 'var(--tonic)'
    case 'subdominant':
      return 'var(--subdominant)'
    case 'dominant':
      return 'var(--dominant)'
    case 'borrowed':
      return 'var(--borrowed)'
    default:
      return 'var(--grid)'
  }
}

export const FUNCTION_NAMES: Record<HarmonicFunction, string> = {
  tonic: 'Tonic',
  subdominant: 'Subdominant',
  dominant: 'Dominant',
  borrowed: 'Outside the key',
}

export function isMinorQuality(quality: string | null | undefined): boolean {
  return !!quality && (quality.startsWith('min') || quality === 'dim' || quality === 'hdim7')
}

/** Guitar fingering: frets per string E A D G B e; -1 muted, 0 open. */
export type Shape = number[]

const OPEN_SHAPES: Record<string, Shape> = {
  'C:maj': [-1, 3, 2, 0, 1, 0], 'D:maj': [-1, -1, 0, 2, 3, 2], 'E:maj': [0, 2, 2, 1, 0, 0], 'G:maj': [3, 2, 0, 0, 0, 3],
  'A:maj': [-1, 0, 2, 2, 2, 0], 'A:min': [-1, 0, 2, 2, 1, 0], 'D:min': [-1, -1, 0, 2, 3, 1], 'E:min': [0, 2, 2, 0, 0, 0],
  'C:7': [-1, 3, 2, 3, 1, 0], 'D:7': [-1, -1, 0, 2, 1, 2], 'E:7': [0, 2, 0, 1, 0, 0], 'G:7': [3, 2, 0, 0, 0, 1],
  'A:7': [-1, 0, 2, 0, 2, 0], 'B:7': [-1, 2, 1, 2, 0, 2], 'A:min7': [-1, 0, 2, 0, 1, 0], 'E:min7': [0, 2, 0, 0, 0, 0],
  'D:min7': [-1, -1, 0, 2, 1, 1], 'C:maj7': [-1, 3, 2, 0, 0, 0], 'F:maj7': [-1, -1, 3, 2, 1, 0],
  'E:maj7': [0, 2, 1, 1, 0, 0], 'D:maj7': [-1, -1, 0, 2, 2, 2], 'G:maj7': [3, 2, 0, 0, 0, 2], 'A:maj7': [-1, 0, 2, 1, 2, 0],
  'A:sus4': [-1, 0, 2, 2, 3, 0], 'D:sus4': [-1, -1, 0, 2, 3, 3], 'E:sus4': [0, 2, 2, 2, 0, 0], 'A:sus2': [-1, 0, 2, 2, 0, 0],
  'D:sus2': [-1, -1, 0, 2, 3, 0],
}

// Movable shapes relative to the root fret (null = muted; a grip may reach one fret below
// the root, as in G6 = 3-x-2-4-3-x). E-shapes put the root on string 6, A-shapes on string 5.
// Every chord type of the large vocabulary has at least one; each was checked to sound
// exactly its chord tones with the root lowest, at every fret.
type RelativeShape = (number | null)[]
const X = null
const E_SHAPES: Record<string, RelativeShape> = {
  maj: [0, 2, 2, 1, 0, 0], min: [0, 2, 2, 0, 0, 0], '7': [0, 2, 0, 1, 0, 0], min7: [0, 2, 0, 0, 0, 0],
  maj7: [0, X, 1, 1, 0, X], sus4: [0, 2, 2, 2, 0, 0], maj6: [0, X, -1, 1, 0, X], min6: [0, X, -1, 0, 0, X],
  dim: [0, 1, 2, 0, X, X], aug: [0, X, 2, 1, 1, 0], dim7: [0, X, -1, 0, -1, X], hdim7: [0, X, 0, 0, -1, X],
  minmaj7: [0, X, 1, 0, 0, X],
}
const A_SHAPES: Record<string, RelativeShape> = {
  maj: [X, 0, 2, 2, 2, 0], min: [X, 0, 2, 2, 1, 0], '7': [X, 0, 2, 0, 2, 0], min7: [X, 0, 2, 0, 1, 0],
  maj7: [X, 0, 2, 1, 2, 0], sus4: [X, 0, 2, 2, 3, 0], sus2: [X, 0, 2, 2, 0, 0], maj6: [X, 0, 2, 2, 2, 2],
  min6: [X, 0, 2, 2, 1, 2], dim: [X, 0, 1, 2, 1, X], aug: [X, 0, 3, 2, 2, 1], dim7: [X, 0, 1, 2, 1, 2],
  hdim7: [X, 0, 1, 0, 1, X], minmaj7: [X, 0, 2, 1, 1, 0],
}

/** A playable guitar shape for a chord, or null if we have none for this quality. */
export function guitarShape(chord: Pick<ChordSegment, 'label' | 'root' | 'quality'>): Shape | null {
  if (!chord.root || !chord.quality) return null
  const pc = noteToPc(chord.root)
  const quality = chord.quality
  for (const [label, shape] of Object.entries(OPEN_SHAPES)) {
    const [root, q] = label.split(':')
    if (noteToPc(root) === pc && q === quality) return shape
  }
  const eFret = (pc - 4 + 12) % 12 || 12
  const aFret = (pc - 9 + 12) % 12 || 12
  const options: [number, RelativeShape | undefined][] = [[eFret, E_SHAPES[quality]], [aFret, A_SHAPES[quality]]]
  const usable = options.filter((o): o is [number, RelativeShape] => !!o[1]).sort((a, b) => a[0] - b[0])
  if (!usable.length) return null
  const [fret, shape] = usable[0]
  return shape.map((f) => (f === null ? -1 : f + fret))
}

export function formatTime(seconds: number): string {
  const s = Math.max(0, seconds)
  return `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`
}

/** Index of the chord sounding at time t (chords sorted by start). */
export function chordIndexAt(starts: number[], t: number): number {
  let lo = 0
  let hi = starts.length - 1
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1
    if (starts[mid] <= t) lo = mid
    else hi = mid - 1
  }
  return Math.max(0, lo)
}

export const MAJOR_CIRCLE = ['C', 'G', 'D', 'A', 'E', 'B', 'F#', 'Db', 'Ab', 'Eb', 'Bb', 'F']
export const MINOR_CIRCLE = ['Am', 'Em', 'Bm', 'F#m', 'C#m', 'G#m', 'D#m', 'Bbm', 'Fm', 'Cm', 'Gm', 'Dm']

/** Position on the circle of fifths (0 = C / Am) for a pitch class. */
export function circleIndex(pc: number, minor: boolean): number {
  const relative = minor ? (pc + 3) % 12 : pc
  return (relative * 7) % 12
}
