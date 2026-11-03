export type HandSide = 'LEFT' | 'RIGHT'
export type AssessmentStatus = 'DRAFT' | 'IN_PROGRESS' | 'COMPLETED'
export type CurrentStepKey = 'BASIC_INPUT' | 'LOCATION_3D' | 'VISUAL_ANNOTATION' | 'AGENT' | 'RESULT'
export type VisualSourceType = 'THREE_D_SNAPSHOT' | 'STANDARD_IMAGE' | 'USER_IMAGE'
export type AnnotationKind = 'POINT' | 'CIRCLE' | 'FREEHAND'
export type OnsetKind = 'TODAY' | 'DAYS_AGO' | 'WEEKS_AGO' | 'MONTHS_AGO' | 'EXACT_DATE' | 'UNKNOWN'
export type FingerCode = 'THUMB' | 'INDEX' | 'MIDDLE' | 'RING' | 'LITTLE'
export type JointCode = 'TIP' | 'DIP' | 'PIP' | 'MCP' | 'CMC'
export type RecommendationLevel = 'SELF_MONITOR' | 'ROUTINE_PROFESSIONAL' | 'PROMPT_PROFESSIONAL' | 'URGENT'
export type SymptomType =
  | 'PAIN'
  | 'ACHING'
  | 'DISTENSION'
  | 'NUMBNESS'
  | 'SWELLING'
  | 'WEAKNESS'
  | 'STIFFNESS'
  | 'OTHER_UNCERTAIN'

export interface NormalizedPoint {
  x: number
  y: number
}

export interface PictureAnnotation {
  id: string
  kind: AnnotationKind
  points: NormalizedPoint[]
  radius?: number
  color: string
  strokeWidth: number
  note?: string
  createdAt: string
}

export interface SymptomLocation {
  locationId?: string
  handSide: HandSide
  regionId: string
  displayLabel: string
  fingerCode?: FingerCode
  jointCode?: JointCode
  detailNote?: string
  selectedAt?: string
}

export interface ViewState3D {
  modelAssetVersion: string
  camera: { x: number; y: number; z: number }
  target: { x: number; y: number; z: number }
  up?: { x: number; y: number; z: number }
  zoom: number
  coarseRegionId?: string
  implementationHit?: Record<string, unknown>
}

export interface VisualSource {
  id: string
  assessmentId: string
  type: VisualSourceType
  pictureUrl: string
  previewUrl?: string
  width?: number
  height?: number
  standardView?: 'PALMAR' | 'DORSAL' | 'LATERAL'
  annotations: PictureAnnotation[]
  description?: string
  uploadStatus?: 'LOCAL' | 'UPLOADING' | 'PROCESSING' | 'READY' | 'FAILED'
  viewState?: ViewState3D
  createdAt: string
}

export interface AssessmentInput {
  handSide: HandSide
  symptomTypes: SymptomType[]
  onsetKind: OnsetKind
  onsetAmount?: number
  onsetDate?: string
  onsetText?: string
  trigger: string
  normalUseImpact: 'NONE' | 'MILD' | 'SIGNIFICANT'
  additionalDescription: string
}

export interface KnowledgeReference {
  title: string
  url?: string | null
  excerpt?: string | null
  sourceType?: 'WEB_SEARCH' | 'KNOWLEDGE'
  documentId?: string | null
  chunkId?: string | null
  contentVersion?: string | null
}

export interface AssessmentResult {
  assessmentId: string
  summary: string
  recommendationLevel: RecommendationLevel
  reasons: string[]
  suggestedNextActions: string[]
  warningSigns: string[]
  safetyNotice: string
  generatedBy: 'MOCK' | 'RULE' | 'AI'
  finalizedAt: string
  references?: KnowledgeReference[]
}

export interface Assessment {
  id: string
  status: AssessmentStatus
  currentStepKey: CurrentStepKey
  input: AssessmentInput
  symptomLocations: SymptomLocation[]
  visualSources: VisualSource[]
  result?: AssessmentResult
  createdAt: string
  updatedAt: string
}

export type AgentQuestionCode =
  | 'CONFIRM_HAND_CONFLICT'
  | 'SYMPTOM_QUALITY'
  | 'SWELLING_OR_NUMBNESS'
  | 'MOVEMENT_OR_PRESSURE'

export interface AgentQuestion {
  code: AgentQuestionCode
  prompt: string
  hint?: string
  options?: Array<{ label: string; value: string }>
  allowText: boolean
  correctionRequired?: boolean
}

export interface AgentExchange {
  id: string
  question: AgentQuestion
  answer?: string
  createdAt: string
}

export interface AgentSession {
  id: string
  assessmentId: string
  status: 'ACTIVE' | 'COMPLETED'
  exchanges: AgentExchange[]
  updatedAt: string
}
