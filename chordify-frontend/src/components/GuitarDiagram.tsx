import type { Shape } from '../lib/music'

/** A chord box. With a capo, frets are counted from the capo, as on a capo chart. */
export function GuitarDiagram({ shape, size = 120, capo = 0 }: { shape: Shape | null; size?: number; capo?: number }) {
  if (!shape) {
    return (
      <svg className="diagram" width={size} height={size * 1.25} viewBox="0 0 120 150" role="img" aria-label="No guitar diagram">
        <text x={60} y={80} textAnchor="middle" fontSize={12} fill="var(--muted)">no diagram</text>
      </svg>
    )
  }
  const x0 = 20, y0 = 28, dx = 16, dy = 24
  const fretted = shape.filter((f) => f > 0)
  const lowest = fretted.length ? Math.min(...fretted) : 1
  const base = Math.max(...shape) > 4 ? lowest : 1
  return (
    <svg className="diagram" width={size} height={size * 1.25} viewBox="0 0 120 150" role="img"
         aria-label={`Guitar chord diagram, frets ${shape.map((f) => (f < 0 ? 'x' : f)).join(' ')}${capo ? `, counted from a capo on fret ${capo}` : ''}`}>
      {capo > 0 && base === 1
        ? <rect x={x0 - 7} y={y0 - 7} width={dx * 5 + 14} height={7} rx={3.5} fill="var(--ink-2)" />
        : <rect x={x0} y={y0 - 3} width={dx * 5} height={base === 1 ? 4 : 1} fill="var(--ink)" />}
      {[0, 1, 2, 3, 4, 5].map((s) => (
        <line key={`s${s}`} x1={x0 + s * dx} y1={y0} x2={x0 + s * dx} y2={y0 + dy * 4} stroke="var(--axis)" />
      ))}
      {[0, 1, 2, 3, 4].map((f) => (
        <line key={`f${f}`} x1={x0} y1={y0 + f * dy} x2={x0 + dx * 5} y2={y0 + f * dy} stroke="var(--axis)" />
      ))}
      {shape.map((fret, s) => {
        const x = x0 + s * dx
        const above = capo > 0 && base === 1 ? y0 - 14 : y0 - 10
        if (fret < 0) return <text key={s} x={x} y={above} textAnchor="middle" fontSize={11} fill="var(--muted)">×</text>
        if (fret === 0) return <circle key={s} cx={x} cy={above - 4} r={4} fill="none" stroke="var(--ink-2)" strokeWidth={1.5} />
        return <circle key={s} cx={x} cy={y0 + (fret - base + 0.5) * dy} r={6} fill="var(--ink)" />
      })}
      {base > 1 && <text x={x0 + dx * 5 + 8} y={y0 + dy * 0.5 + 4} fontSize={11} fill="var(--muted)">{base}fr</text>}
      <text x={60} y={146} textAnchor="middle" fontSize={12} fill="var(--muted)">{capo ? `guitar · capo ${capo}` : 'guitar'}</text>
    </svg>
  )
}
