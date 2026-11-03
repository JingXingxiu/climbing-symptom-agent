import type { AssessmentApi } from './contracts'
import { HttpAssessmentApi } from './http'
import { MockAssessmentApi } from './mock'

export type { AssessmentApi } from './contracts'

const mode = import.meta.env.VITE_API_MODE ?? 'mock'

export const api: AssessmentApi = mode === 'http'
  ? new HttpAssessmentApi(import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080')
  : new MockAssessmentApi()
