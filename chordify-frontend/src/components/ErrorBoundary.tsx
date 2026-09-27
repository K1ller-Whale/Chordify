import { Component, type ErrorInfo, type ReactNode } from 'react'

interface Props {
  children: ReactNode
  onReset: () => void
}

interface State {
  error: Error | null
}

/** Shows what went wrong, and a way out, instead of an empty page when a view fails to draw. */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error(error, info.componentStack)
  }

  render() {
    const { error } = this.state
    if (!error) return this.props.children
    return (
      <section className="card error-card" role="alert">
        <h2>This page could not be shown</h2>
        <p>{error.message}</p>
        <p className="small">The analysis itself is safe. Resetting clears this page's settings, such as the capo, and reloads it.</p>
        <button className="button" onClick={this.props.onReset}>Reset and reload</button>{' '}
        <a className="button" href="#/">Back to start</a>
      </section>
    )
  }
}
