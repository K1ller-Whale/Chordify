import type {
  AnalysisOptions,
  AnalysisResult,
  AnalysisStatus,
  LegacyChordPrediction,
  Problem,
  ProgressEvent,
  ProgressionRequest,
  ProgressionResponse,
} from './types'

export const API_BASE = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8000').replace(/\/$/, '')

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

async function parseError(response: Response): Promise<ApiError> {
  try {
    const body = (await response.json()) as Problem
    return new ApiError(response.status, body.code ?? 'ERROR', body.detail ?? body.title ?? response.statusText)
  } catch {
    return new ApiError(response.status, 'ERROR', response.statusText || 'Request failed')
  }
}

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${API_BASE}${path}`, init)
  } catch {
    throw new ApiError(0, 'NETWORK', `Cannot reach the Chordify API at ${API_BASE}.`)
  }
  if (!response.ok) throw await parseError(response)
  return (await response.json()) as T
}

/** Upload with progress (fetch has no upload progress events). */
export function createAnalysis(file: Blob, filename: string, onProgress?: (fraction: number) => void,
                               options?: AnalysisOptions): Promise<AnalysisStatus> {
  return new Promise((resolve, reject) => {
    const form = new FormData()
    form.append('file', file, filename)
    if (options) form.append('options', JSON.stringify(options))
    const xhr = new XMLHttpRequest()
    xhr.open('POST', `${API_BASE}/api/v2/analyses`)
    xhr.responseType = 'json'
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress?.(event.loaded / event.total)
    }
    xhr.onerror = () => reject(new ApiError(0, 'NETWORK', `Cannot reach the Chordify API at ${API_BASE}.`))
    xhr.onload = () => {
      const body = xhr.response as AnalysisStatus | Problem | null
      if (xhr.status === 200 || xhr.status === 202) resolve(body as AnalysisStatus)
      else {
        const problem = (body ?? {}) as Partial<Problem>
        reject(new ApiError(xhr.status, problem.code ?? 'ERROR', problem.detail ?? 'Upload failed'))
      }
    }
    xhr.send(form)
  })
}

export const getStatus = (id: string) => json<AnalysisStatus>(`/api/v2/analyses/${id}`)
export const getResult = (id: string) => json<AnalysisResult>(`/api/v2/analyses/${id}/result`)
export const listAnalyses = () => json<AnalysisStatus[]>('/api/v2/analyses')
export const exportUrl = (id: string, format: 'lab' | 'json') => `${API_BASE}/api/v2/analyses/${id}/export?format=${format}`

export const nextChords = (body: ProgressionRequest) =>
  json<ProgressionResponse>('/api/v2/progressions/next', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })

export async function predictSingleChord(file: Blob, filename: string): Promise<LegacyChordPrediction> {
  const form = new FormData()
  form.append('file', file, filename)
  return json<LegacyChordPrediction>('/predict', { method: 'POST', body: form })
}

export interface AnalysisEvents {
  onProgress: (event: ProgressEvent) => void
  onCompleted: () => void
  onFailed: (error: { code: string; message: string }) => void
}

/** Server-Sent Events for one analysis. Returns a function that closes the stream. */
export function subscribeToAnalysis(id: string, handlers: AnalysisEvents): () => void {
  const source = new EventSource(`${API_BASE}/api/v2/analyses/${id}/events`)
  const parse = (event: MessageEvent) => JSON.parse(event.data as string)
  source.addEventListener('snapshot', (event) => {
    const snapshot = parse(event as MessageEvent) as { status: string; stage: string | null; progress: number; error: { code: string; message: string } | null }
    if (snapshot.status === 'completed') {
      handlers.onCompleted()
      source.close()
    } else if (snapshot.status === 'failed' && snapshot.error) {
      handlers.onFailed(snapshot.error)
      source.close()
    } else {
      handlers.onProgress({ stage: snapshot.stage ?? 'queued', progress: snapshot.progress })
    }
  })
  source.addEventListener('progress', (event) => handlers.onProgress(parse(event as MessageEvent) as ProgressEvent))
  source.addEventListener('completed', () => {
    handlers.onCompleted()
    source.close()
  })
  source.addEventListener('failed', (event) => {
    handlers.onFailed(parse(event as MessageEvent) as { code: string; message: string })
    source.close()
  })
  source.addEventListener('cancelled', () => {
    handlers.onFailed({ code: 'CANCELLED', message: 'The analysis was cancelled.' })
    source.close()
  })
  return () => source.close()
}
