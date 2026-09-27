/** Local audio: the file the user uploaded is kept in memory for playback (the server
 * never streams copyrighted audio back), plus waveform peaks and a WAV recorder. */

const files = new Map<string, File>()

export const rememberFile = (analysisId: string, file: File) => files.set(analysisId, file)
export const recallFile = (analysisId: string) => files.get(analysisId)

export async function sha256(blob: Blob): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer())
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

/** Peak amplitude per ``step`` seconds, normalised to 0..1. */
export async function waveformPeaks(blob: Blob, step = 0.1): Promise<number[]> {
  const context = new AudioContext()
  try {
    const buffer = await context.decodeAudioData(await blob.arrayBuffer())
    const data = buffer.getChannelData(0)
    const hop = Math.max(1, Math.floor(buffer.sampleRate * step))
    const peaks: number[] = []
    let max = 0
    for (let i = 0; i < data.length; i += hop) {
      let peak = 0
      for (let j = i; j < Math.min(i + hop, data.length); j++) peak = Math.max(peak, Math.abs(data[j]))
      peaks.push(peak)
      max = Math.max(max, peak)
    }
    return peaks.map((p) => (max > 0 ? p / max : 0))
  } finally {
    void context.close()
  }
}

function encodeWav(chunks: Float32Array[], sampleRate: number): Blob {
  const length = chunks.reduce((n, c) => n + c.length, 0)
  const buffer = new ArrayBuffer(44 + length * 2)
  const view = new DataView(buffer)
  const text = (offset: number, s: string) => [...s].forEach((ch, i) => view.setUint8(offset + i, ch.charCodeAt(0)))
  text(0, 'RIFF')
  view.setUint32(4, 36 + length * 2, true)
  text(8, 'WAVE')
  text(12, 'fmt ')
  view.setUint32(16, 16, true)
  view.setUint16(20, 1, true) // PCM
  view.setUint16(22, 1, true) // mono
  view.setUint32(24, sampleRate, true)
  view.setUint32(28, sampleRate * 2, true)
  view.setUint16(32, 2, true)
  view.setUint16(34, 16, true)
  text(36, 'data')
  view.setUint32(40, length * 2, true)
  let offset = 44
  for (const chunk of chunks) {
    for (let i = 0; i < chunk.length; i++, offset += 2) {
      const s = Math.max(-1, Math.min(1, chunk[i]))
      view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7fff, true)
    }
  }
  return new Blob([buffer], { type: 'audio/wav' })
}

const WORKLET = `
class Capture extends AudioWorkletProcessor {
  process(inputs) {
    const channel = inputs[0] && inputs[0][0]
    if (channel) this.port.postMessage(channel.slice(0))
    return true
  }
}
registerProcessor('chordify-capture', Capture)
`

/** Records the microphone as 16-bit mono WAV, so the server needs no webm/ffmpeg support. */
export class WavRecorder {
  private context: AudioContext | null = null
  private stream: MediaStream | null = null
  private node: AudioWorkletNode | null = null
  private chunks: Float32Array[] = []
  onLevel: ((rms: number) => void) | null = null

  async start() {
    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false },
    })
    this.context = new AudioContext()
    const url = URL.createObjectURL(new Blob([WORKLET], { type: 'application/javascript' }))
    await this.context.audioWorklet.addModule(url)
    URL.revokeObjectURL(url)
    const source = this.context.createMediaStreamSource(this.stream)
    this.node = new AudioWorkletNode(this.context, 'chordify-capture')
    this.chunks = []
    this.node.port.onmessage = (event: MessageEvent<Float32Array>) => {
      this.chunks.push(event.data)
      if (this.onLevel && this.chunks.length % 8 === 0) {
        const data = event.data
        this.onLevel(Math.sqrt(data.reduce((sum, v) => sum + v * v, 0) / data.length))
      }
    }
    source.connect(this.node)
  }

  get seconds(): number {
    const rate = this.context?.sampleRate ?? 44100
    return this.chunks.reduce((n, c) => n + c.length, 0) / rate
  }

  async stop(): Promise<Blob> {
    const rate = this.context?.sampleRate ?? 44100
    this.node?.disconnect()
    this.stream?.getTracks().forEach((track) => track.stop())
    await this.context?.close()
    const blob = encodeWav(this.chunks, rate)
    this.chunks = []
    return blob
  }
}
