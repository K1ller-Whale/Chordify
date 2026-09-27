import { useEffect, useState } from 'react'
import { AnalysisPage } from './pages/AnalysisPage'
import { Home } from './pages/Home'
import { QuickChord } from './pages/QuickChord'
import { Songwriter } from './pages/Songwriter'

function useHash(): [string, (hash: string) => void] {
  const [hash, setHash] = useState(() => window.location.hash || '#/')
  useEffect(() => {
    const onChange = () => setHash(window.location.hash || '#/')
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])
  return [hash, (next: string) => { window.location.hash = next }]
}

export default function App() {
  const [hash, navigate] = useHash()
  const analysis = hash.match(/^#\/a\/([\w-]+)/)
  let page
  if (analysis) page = <AnalysisPage key={analysis[1]} id={analysis[1]} />
  else if (hash.startsWith('#/quick')) page = <QuickChord />
  else if (hash.startsWith('#/write')) page = <Songwriter />
  else page = <Home navigate={navigate} />
  const active = (prefix: string) => (hash.startsWith(prefix) ? 'page' : undefined)
  return (
    <div className="app">
      <nav className="topbar">
        <a className="brand" href="#/">Chordify</a>
        <a href="#/" aria-current={hash === '#/' ? 'page' : undefined}>Analyse</a>
        <a href="#/quick" aria-current={active('#/quick')}>Quick chord</a>
        <a href="#/write" aria-current={active('#/write')}>Songwriter</a>
      </nav>
      <main>{page}</main>
    </div>
  )
}
