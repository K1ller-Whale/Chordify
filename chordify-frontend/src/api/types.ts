// Types generated from the backend's OpenAPI document (npm run api:types).
import type { components } from './schema'

type Schemas = components['schemas']

export type AnalysisResult = Schemas['AnalysisResult']
export type AnalysisStatus = Schemas['AnalysisStatus']
export type ChordSegment = Schemas['ChordSegment']
export type Prediction = Schemas['Prediction']
export type PriorEntry = Schemas['PriorEntry']
export type Bar = Schemas['Bar']
export type Pattern = Schemas['Pattern']
export type TimeShare = Schemas['TimeShare']
export type ProgressionRequest = Schemas['ProgressionRequest']
export type ProgressionResponse = Schemas['ProgressionResponse']
export type LegacyChordPrediction = Schemas['LegacyChordPrediction']

export type HarmonicFunction = 'tonic' | 'subdominant' | 'dominant' | 'borrowed'

export interface Problem {
  type: string
  title: string
  status: number
  code: string
  detail: string
}

export interface ProgressEvent {
  stage: string
  progress: number
}
