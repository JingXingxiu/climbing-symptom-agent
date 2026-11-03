import type { AddVisualSourceInput, AssessmentApi } from './contracts'
import type {
  AgentQuestion as DomainAgentQuestion,
  AgentSession,
  Assessment,
  AssessmentInput,
  AssessmentResult,
  PictureAnnotation,
  SymptomLocation,
  VisualSource,
  VisualSourceType as DomainVisualSourceType,
} from '@/types/domain'
import { createUuid } from '@/utils/id'
import { bearerHeader, HttpStatusError, requestJson, type HttpMethod } from './http-client'

type AssessmentStatus = 'DRAFT' | 'COLLECTING' | 'CLARIFYING' | 'COMPLETED' | 'CANCELLED'
type CurrentStepKey = 'BASIC_INPUT' | 'LOCATION_3D' | 'VISUAL_ANNOTATION' | 'AGENT' | 'RESULT'
type HandSide = 'LEFT' | 'RIGHT'
type VisualSourceType = 'MODEL_SNAPSHOT' | 'STANDARD_REFERENCE' | 'USER_UPLOAD'
type OnsetKind = 'TODAY' | 'DAYS_AGO' | 'WEEKS_AGO' | 'MONTHS_AGO' | 'EXACT_DATE' | 'UNKNOWN'
type SymptomType =
  | 'PAIN'
  | 'ACHING'
  | 'DISTENSION'
  | 'NUMBNESS'
  | 'SWELLING'
  | 'WEAKNESS'
  | 'STIFFNESS'
  | 'OTHER_UNCERTAIN'
type FingerCode = 'THUMB' | 'INDEX' | 'MIDDLE' | 'RING' | 'LITTLE'
type JointCode = 'TIP' | 'DIP' | 'PIP' | 'MCP' | 'CMC'
type RecommendationLevel = 'SELF_MONITOR' | 'ROUTINE_PROFESSIONAL' | 'PROMPT_PROFESSIONAL' | 'URGENT'

interface Vector3 {
  x: number
  y: number
  z: number
}

interface ViewState3D {
  modelAssetVersion: string
  camera: Vector3
  target: Vector3
  up?: Vector3
  zoom: number
  coarseRegionId?: string | null
  implementationHit?: Record<string, unknown> | null
}

interface InitialSymptomDetails {
  handSide: HandSide
  symptomTypes?: SymptomType[]
  onsetKind: OnsetKind
  onsetAmount?: number | null
  onsetDate?: string | null
  onsetText?: string | null
  activityContext: string
  normalUseImpact: 'NONE' | 'MILD' | 'MODERATE' | 'SEVERE' | 'UNKNOWN'
  userNote?: string | null
}

interface CreateAssessmentRequest {
  initialInput?: InitialSymptomDetails | null
}

interface UpdateAssessmentBasicsRequest {
  initialInput: InitialSymptomDetails
  currentStepKey?: CurrentStepKey | null
  version: number
}

interface SubmitAssessmentRequest {
  version: number
}

interface SymptomLocationInput {
  handSide: HandSide
  regionId: string
  displayLabel: string
  fingerCode?: FingerCode | null
  jointCode?: JointCode | null
  detailNote?: string | null
}

interface ReplaceSymptomLocationsRequest {
  symptomLocations: SymptomLocationInput[]
}

interface ReplaceVisualReferencesRequest {
  pictureIds: string[]
  version: number
}

interface SymptomLocationResponse {
  locationId: string
  handSide: HandSide
  regionId: string
  displayLabel: string
  fingerCode?: FingerCode | null
  jointCode?: JointCode | null
  detailNote?: string | null
  selectedAt: string
}

interface VisualReference {
  pictureId: string
  sourceType: VisualSourceType
  annotationSetId?: string | null
  sortOrder: number
}

interface AssessmentAnswerResponse {
  answerId: string
  clientAnswerId: string
  sequenceNumber: number
  questionKey: string
  questionText?: string | null
  answer: unknown
  source: 'INITIAL_FORM' | 'MOCK_AGENT' | 'AI_AGENT' | 'USER_CORRECTION'
  createdAt: string
}

interface AssessmentResponse {
  assessmentId: string
  userId: string
  status: AssessmentStatus
  initialInput?: InitialSymptomDetails | null
  symptomLocations: SymptomLocationResponse[]
  visualReferences: VisualReference[]
  answers: AssessmentAnswerResponse[]
  currentStepKey: CurrentStepKey
  version: number
  createdAt: string
  updatedAt: string
  completedAt?: string | null
}

interface AssessmentSummary {
  assessmentId: string
  status: AssessmentStatus
  handSide?: HandSide | null
  locationLabel?: string | null
  activityContext?: string | null
  recommendationLevel?: RecommendationLevel | null
  updatedAt: string
}

interface AssessmentPageResponse {
  items: AssessmentSummary[]
  nextCursor?: string | null
  hasMore: boolean
}

interface AssessmentResultResponse {
  assessmentId: string
  recommendationLevel: RecommendationLevel
  summary: string
  reasons: string[]
  suggestedNextActions: string[]
  warningSigns: string[]
  safetyNotice: string
  generatedBy: 'MOCK' | 'RULE' | 'AI'
  finalizedAt: string
}

interface PictureUploadMetadata {
  sourceType: VisualSourceType
  clientRequestId: string
  assessmentId?: string | null
  viewState?: ViewState3D | null
  userNote?: string | null
}

interface PictureResponse {
  pictureId: string
  ownerUserId: string | null
  sourceType: VisualSourceType
  standardAssetKey?: string | null
  standardHandSide?: HandSide | null
  standardView?: 'PALMAR' | 'DORSAL' | 'SIDE' | null
  originalFilename?: string | null
  mediaType: string
  byteSize: number
  width?: number | null
  height?: number | null
  uploadStatus: 'INITIATED' | 'UPLOADED' | 'AVAILABLE' | 'FAILED' | 'DELETED'
  processingStatus: 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED' | 'NOT_REQUIRED'
  originalUrl?: string | null
  thumbnailUrl?: string | null
  viewState?: ViewState3D | null
  createdAt: string
  updatedAt: string
}

interface AnnotationShape {
  shapeId: string
  type: 'POINT' | 'CIRCLE' | 'POLYLINE' | 'POLYGON'
  geometry:
    | { kind: 'point'; point: { x: number; y: number } }
    | { kind: 'circle'; center: { x: number; y: number }; radiusX: number; radiusY: number }
    | { kind: 'polyline' | 'polygon'; points: Array<{ x: number; y: number }> }
  color?: string
  lineWidthNormalized?: number
  label?: string | null
}

interface ReplaceAnnotationSetRequest {
  assessmentId: string
  sourceWidth: number
  sourceHeight: number
  shapes: AnnotationShape[]
  note?: string | null
  version: number
}

interface AnnotationSetResponse {
  annotationSetId: string
  pictureId: string
  coordinateSpace: 'NORMALIZED_0_1'
  sourceWidth: number
  sourceHeight: number
  shapes: AnnotationShape[]
  note?: string | null
  version: number
  updatedAt: string
}

interface AgentQuestionOption {
  value: string
  label: string
}

interface AgentQuestion {
  questionId: string
  questionKey: string
  prompt: string
  helpText?: string | null
  answerType: 'SINGLE_CHOICE' | 'MULTI_CHOICE' | 'BOOLEAN' | 'TEXT'
  options?: AgentQuestionOption[]
  required: boolean
}

interface AgentStepSummary {
  stepNumber: number
  stepType: 'USER_INPUT' | 'EXTRACT_INFORMATION' | 'NARROW_DOWN' | 'DETECT_CONTRADICTION' | 'REQUEST_CONFIRMATION' | 'OUTPUT_RESULT'
  message: string
  createdAt: string
  stepId: string
  parentStepId: string | null
  status: 'STARTED' | 'WAITING' | 'COMPLETED' | 'FAILED' | 'DISCARDED'
}

interface CreateAgentSessionRequest {
  assessmentId: string
  preferredLocale?: string
  modelProvider?: string
  modelName?: string
  sourceSessionId?: string
  sourceCheckpointId?: string
}

interface AnswerAgentQuestionRequest {
  questionId: string
  answer: unknown
  expectedStepNumber?: number
  expectedVersion: number
  baseCheckpointId: string
}

interface AgentSessionResponse {
  sessionId: string
  assessmentId: string
  status: 'CREATED' | 'RUNNING' | 'WAITING_FOR_USER' | 'PAUSED' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  currentStepNumber: number
  behavior?: 'NARROW_DOWN' | 'CLARIFY_CONTRADICTION' | 'PRODUCE_RESULT' | null
  question?: AgentQuestion | null
  recentSteps: AgentStepSummary[]
  terminationReason?: string | null
  updatedAt: string
  version: number
  activeCheckpointId: string
  modelProvider: string
  modelName: string
  totalModelCalls: number
  maxModelCalls: number
  maxSteps: number
}

interface AgentFinalizationResponse {
  assessmentId: string
  sessionId: string
  resultId: string
  revisionNo: number
  finalizedAt: string
}

interface AgentOutcomeResponse {
  sessionId: string
  assessmentId: string
  recommendationLevel: RecommendationLevel
  summary: string
  reasons: string[]
  suggestedNextActions: string[]
  warningSigns: string[]
  safetyNotice: string
  finalizedAt: string
  uncertaintyNote?: string | null
}

const EMPTY_INPUT: AssessmentInput = {
  handSide: 'RIGHT',
  symptomTypes: [],
  onsetKind: 'UNKNOWN',
  onsetText: '',
  trigger: '',
  normalUseImpact: 'NONE',
  additionalDescription: '',
}

function toApiSourceType(value: DomainVisualSourceType): VisualSourceType {
  return value === 'THREE_D_SNAPSHOT'
    ? 'MODEL_SNAPSHOT'
    : value === 'STANDARD_IMAGE' ? 'STANDARD_REFERENCE' : 'USER_UPLOAD'
}

function toDomainSourceType(value: VisualSourceType): DomainVisualSourceType {
  return value === 'MODEL_SNAPSHOT'
    ? 'THREE_D_SNAPSHOT'
    : value === 'STANDARD_REFERENCE' ? 'STANDARD_IMAGE' : 'USER_IMAGE'
}

function toInitialSymptomDetails(input: AssessmentInput): InitialSymptomDetails {
  return {
    handSide: input.handSide,
    symptomTypes: input.symptomTypes,
    onsetKind: input.onsetKind,
    onsetAmount: input.onsetAmount ?? null,
    onsetDate: input.onsetDate ?? null,
    onsetText: input.onsetText ?? null,
    activityContext: input.trigger,
    normalUseImpact: input.normalUseImpact === 'SIGNIFICANT' ? 'SEVERE' : input.normalUseImpact,
    userNote: input.additionalDescription || null,
  }
}

function toAssessmentInput(input?: InitialSymptomDetails | null): AssessmentInput {
  if (!input) return { ...EMPTY_INPUT }
  return {
    handSide: input.handSide,
    symptomTypes: Array.isArray(input.symptomTypes) ? input.symptomTypes : [],
    onsetKind: input.onsetKind,
    onsetAmount: input.onsetAmount ?? undefined,
    onsetDate: input.onsetDate ?? undefined,
    onsetText: input.onsetText ?? undefined,
    trigger: input.activityContext,
    normalUseImpact: input.normalUseImpact === 'NONE'
      ? 'NONE'
      : input.normalUseImpact === 'MILD' ? 'MILD' : 'SIGNIFICANT',
    additionalDescription: input.userNote ?? '',
  }
}

function domainStatus(status: AssessmentStatus): Assessment['status'] {
  if (status === 'COMPLETED') return 'COMPLETED'
  if (status === 'DRAFT' || status === 'CANCELLED') return 'DRAFT'
  return 'IN_PROGRESS'
}

function localImageSize(src: string): Promise<{ width?: number; height?: number }> {
  return new Promise((resolve) => {
    uni.getImageInfo({
      src,
      success: (result) => resolve({ width: result.width, height: result.height }),
      fail: () => resolve({}),
    })
  })
}

export class HttpAssessmentApi implements AssessmentApi {
  private readonly baseUrl: string
  private readonly versions = new Map<string, number>()
  private readonly assessmentCache = new Map<string, Assessment>()
  private readonly visualSourceCache = new Map<string, VisualSource>()
  private readonly annotationVersions = new Map<string, number>()
  private readonly agentCache = new Map<string, AgentSession>()
  private readonly agentResponses = new Map<string, AgentSessionResponse>()
  private readonly pendingAgentAnswers = new Map<string, {
    key: string
    text: string
    request: AnswerAgentQuestionRequest
  }>()

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl.replace(/\/$/, '')
  }

  async bootstrap(): Promise<void> {
    // Token restoration/login is owned by base-service and intentionally not
    // faked by this adapter. A caller may place a bearer token in storage.
  }

  private request<TResponse, TRequest extends object = never>(
    path: string,
    method: HttpMethod = 'GET',
    data?: TRequest,
    header?: Record<string, string>,
    timeoutMs?: number,
  ): Promise<TResponse> {
    return requestJson<TResponse, TRequest>(this.baseUrl, path, method, data, header, timeoutMs)
  }

  private async optional<T>(path: string): Promise<T | undefined> {
    try {
      return await this.request<T>(path)
    } catch (error) {
      if (error instanceof HttpStatusError && error.status === 404) return undefined
      throw error
    }
  }

  private toAnnotationShapes(source: VisualSource, annotations: PictureAnnotation[]): AnnotationShape[] {
    const width = Math.max(1, source.width ?? 1)
    const height = Math.max(1, source.height ?? 1)
    const minimum = Math.min(width, height)
    return annotations.map((annotation): AnnotationShape => {
      if (annotation.kind === 'POINT') {
        return {
          shapeId: annotation.id,
          type: 'POINT',
          geometry: { kind: 'point', point: annotation.points[0] ?? { x: 0.5, y: 0.5 } },
          color: annotation.color,
          lineWidthNormalized: Math.min(0.05, annotation.strokeWidth / minimum),
          label: annotation.note ?? null,
        }
      }
      if (annotation.kind === 'CIRCLE') {
        const radius = annotation.radius ?? 0.06
        return {
          shapeId: annotation.id,
          type: 'CIRCLE',
          geometry: {
            kind: 'circle',
            center: annotation.points[0] ?? { x: 0.5, y: 0.5 },
            radiusX: Math.min(1, radius * minimum / width),
            radiusY: Math.min(1, radius * minimum / height),
          },
          color: annotation.color,
          lineWidthNormalized: Math.min(0.05, annotation.strokeWidth / minimum),
          label: annotation.note ?? null,
        }
      }
      return {
        shapeId: annotation.id,
        type: 'POLYLINE',
        geometry: { kind: 'polyline', points: annotation.points },
        color: annotation.color,
        lineWidthNormalized: Math.min(0.05, annotation.strokeWidth / minimum),
        label: annotation.note ?? null,
      }
    })
  }

  private toPictureAnnotations(set?: AnnotationSetResponse): PictureAnnotation[] {
    if (!set) return []
    const minimum = Math.min(set.sourceWidth, set.sourceHeight)
    return set.shapes.map((shape) => {
      if (shape.geometry.kind === 'point') {
        return {
          id: shape.shapeId,
          kind: 'POINT',
          points: [shape.geometry.point],
          color: shape.color ?? '#E5484D',
          strokeWidth: (shape.lineWidthNormalized ?? 0.006) * minimum,
          note: shape.label ?? undefined,
          createdAt: set.updatedAt,
        }
      }
      if (shape.geometry.kind === 'circle') {
        const radius = ((shape.geometry.radiusX * set.sourceWidth) + (shape.geometry.radiusY * set.sourceHeight)) / (2 * minimum)
        return {
          id: shape.shapeId,
          kind: 'CIRCLE',
          points: [shape.geometry.center],
          radius,
          color: shape.color ?? '#E5484D',
          strokeWidth: (shape.lineWidthNormalized ?? 0.006) * minimum,
          note: shape.label ?? undefined,
          createdAt: set.updatedAt,
        }
      }
      return {
        id: shape.shapeId,
        kind: 'FREEHAND',
        points: shape.geometry.points,
        color: shape.color ?? '#E5484D',
        strokeWidth: (shape.lineWidthNormalized ?? 0.006) * minimum,
        note: shape.label ?? undefined,
        createdAt: set.updatedAt,
      }
    })
  }

  private async pictureToVisual(
    picture: PictureResponse,
    assessmentId: string,
    standardView?: VisualSource['standardView'],
    localFallback?: { pictureUrl: string; width?: number; height?: number },
  ): Promise<VisualSource> {
    const annotationSet = await this.optional<AnnotationSetResponse>(
      `/api/image/v1/pictures/${picture.pictureId}/annotation-set?assessmentId=${encodeURIComponent(assessmentId)}`,
    )
    this.annotationVersions.set(`${assessmentId}:${picture.pictureId}`, annotationSet?.version ?? 0)
    const resolvedStandardView = standardView
      ?? (picture.standardView === 'SIDE' ? 'LATERAL' : picture.standardView ?? undefined)
    const source: VisualSource = {
      id: picture.pictureId,
      assessmentId,
      type: toDomainSourceType(picture.sourceType),
      pictureUrl: picture.originalUrl ?? picture.thumbnailUrl ?? localFallback?.pictureUrl ?? '',
      previewUrl: picture.thumbnailUrl ?? picture.originalUrl ?? localFallback?.pictureUrl ?? '',
      width: picture.width ?? annotationSet?.sourceWidth ?? localFallback?.width,
      height: picture.height ?? annotationSet?.sourceHeight ?? localFallback?.height,
      standardView: resolvedStandardView,
      uploadStatus: picture.uploadStatus === 'FAILED'
        ? 'FAILED'
        : picture.processingStatus === 'PENDING' || picture.processingStatus === 'PROCESSING' ? 'PROCESSING' : 'READY',
      viewState: picture.viewState ? {
        modelAssetVersion: picture.viewState.modelAssetVersion,
        camera: picture.viewState.camera,
        target: picture.viewState.target,
        up: picture.viewState.up,
        zoom: picture.viewState.zoom,
        coarseRegionId: picture.viewState.coarseRegionId ?? undefined,
        implementationHit: picture.viewState.implementationHit ?? undefined,
      } : undefined,
      annotations: this.toPictureAnnotations(annotationSet),
      description: annotationSet?.note ?? undefined,
      createdAt: picture.createdAt,
    }
    this.visualSourceCache.set(source.id, source)
    return source
  }

  private toAssessmentResult(result: AssessmentResultResponse): AssessmentResult {
    return {
      assessmentId: result.assessmentId,
      summary: result.summary,
      recommendationLevel: result.recommendationLevel,
      reasons: result.reasons,
      suggestedNextActions: result.suggestedNextActions,
      warningSigns: result.warningSigns,
      safetyNotice: result.safetyNotice,
      generatedBy: result.generatedBy,
      finalizedAt: result.finalizedAt,
    }
  }

  private async toAssessment(response: AssessmentResponse): Promise<Assessment> {
    this.versions.set(response.assessmentId, response.version)
    const visualSources = await Promise.all(
      [...response.visualReferences]
        .sort((left, right) => left.sortOrder - right.sortOrder)
        .map(async (reference) => {
          const picture = await this.request<PictureResponse>(`/api/image/v1/pictures/${reference.pictureId}`)
          return this.pictureToVisual(picture, response.assessmentId)
        }),
    )
    const result = response.status === 'COMPLETED'
      ? await this.optional<AssessmentResultResponse>(`/api/history/v1/assessments/${response.assessmentId}/result`)
      : undefined
    const assessment: Assessment = {
      id: response.assessmentId,
      status: domainStatus(response.status),
      currentStepKey: response.status === 'COMPLETED' ? 'RESULT' : response.currentStepKey,
      input: toAssessmentInput(response.initialInput),
      symptomLocations: response.symptomLocations.map((location) => ({
        locationId: location.locationId,
        handSide: location.handSide,
        regionId: location.regionId,
        displayLabel: location.displayLabel,
        fingerCode: location.fingerCode ?? undefined,
        jointCode: location.jointCode ?? undefined,
        detailNote: location.detailNote ?? undefined,
        selectedAt: location.selectedAt,
      })),
      visualSources,
      result: result ? this.toAssessmentResult(result) : undefined,
      createdAt: response.createdAt,
      updatedAt: response.updatedAt,
    }
    this.assessmentCache.set(assessment.id, assessment)
    return assessment
  }

  async createAssessment(input: Partial<AssessmentInput> = {}): Promise<Assessment> {
    const hasInitialInput = Boolean(input.symptomTypes?.length || input.onsetText || input.onsetDate || input.trigger || input.additionalDescription)
    const request: CreateAssessmentRequest = hasInitialInput
      ? { initialInput: toInitialSymptomDetails({ ...EMPTY_INPUT, ...input }) }
      : {}
    const response = await this.request<AssessmentResponse, CreateAssessmentRequest>(
      '/api/history/v1/assessments',
      'POST',
      request,
      { 'X-Idempotency-Key': createUuid() },
    )
    return this.toAssessment(response)
  }

  async getAssessment(id: string): Promise<Assessment> {
    const response = await this.request<AssessmentResponse>(`/api/history/v1/assessments/${id}`)
    return this.toAssessment(response)
  }

  async saveAssessment(assessment: Assessment): Promise<Assessment> {
    const version = this.versions.get(assessment.id) ?? 1
    const updateRequest: UpdateAssessmentBasicsRequest = {
      initialInput: toInitialSymptomDetails(assessment.input),
      currentStepKey: assessment.currentStepKey,
      version,
    }
    const updated = await this.request<AssessmentResponse, UpdateAssessmentBasicsRequest>(
      `/api/history/v1/assessments/${assessment.id}`,
      'PATCH',
      updateRequest,
    )
    this.versions.set(assessment.id, updated.version)

    if (assessment.symptomLocations.length) {
      const replaceLocationsRequest: ReplaceSymptomLocationsRequest = {
        symptomLocations: assessment.symptomLocations.map((location) => ({
          handSide: location.handSide,
          regionId: location.regionId,
          displayLabel: location.displayLabel,
          fingerCode: location.fingerCode ?? null,
          jointCode: location.jointCode ?? null,
          detailNote: location.detailNote ?? null,
        })),
      }
      await this.request<SymptomLocationResponse[], ReplaceSymptomLocationsRequest>(
        `/api/history/v1/assessments/${assessment.id}/symptom-locations`,
        'PUT',
        replaceLocationsRequest,
      )
    } else {
      await this.request<void>(
        `/api/history/v1/assessments/${assessment.id}/symptom-locations`,
        'DELETE',
      )
    }

    // Saving or clearing locations also increments the root version. Its
    // response does not carry that version, so read it before replacing visuals.
    const afterLocations = await this.request<AssessmentResponse>(`/api/history/v1/assessments/${assessment.id}`)
    this.versions.set(assessment.id, afterLocations.version)
    const replaceVisualsRequest: ReplaceVisualReferencesRequest = {
      pictureIds: assessment.visualSources.map((source) => source.id),
      version: afterLocations.version,
    }
    await this.request<VisualReference[], ReplaceVisualReferencesRequest>(
      `/api/history/v1/assessments/${assessment.id}/visual-references`,
      'PUT',
      replaceVisualsRequest,
    )

    // Replacing visuals also increments the Assessment version, but returns only
    // references. Reload rather than cache the earlier PATCH response/version.
    return this.getAssessment(assessment.id)
  }

  async listAssessments(): Promise<Assessment[]> {
    const page = await this.request<AssessmentPageResponse>('/api/history/v1/assessments?limit=20')
    return Promise.all(page.items.map((item) => this.getAssessment(item.assessmentId)))
  }

  private uploadPicture(
    assessmentId: string,
    filePath: string,
    type: VisualSourceType,
    viewState?: ViewState3D,
  ): Promise<PictureResponse> {
    const requestId = createUuid()
    const metadata: PictureUploadMetadata = {
      sourceType: type,
      clientRequestId: requestId,
      assessmentId,
      viewState: viewState ?? null,
    }
    return new Promise((resolve, reject) => {
      uni.uploadFile({
        url: `${this.baseUrl}/api/image/v1/pictures`,
        filePath,
        name: 'file',
        formData: {
          metadata: JSON.stringify(metadata),
        },
        header: {
          'X-Idempotency-Key': requestId,
          ...bearerHeader(),
        },
        success: (response) => {
          if (response.statusCode < 200 || response.statusCode >= 300) {
            reject(new HttpStatusError(response.statusCode, `上传失败（${response.statusCode}）`))
            return
          }
          try {
            resolve(JSON.parse(response.data) as PictureResponse)
          } catch {
            reject(new Error('上传响应格式不正确'))
          }
        },
        fail: (error) => reject(new Error(error.errMsg)),
      })
    })
  }

  private async attachVisual(assessmentId: string, source: VisualSource): Promise<VisualSource> {
    const cached = this.assessmentCache.get(assessmentId) ?? await this.getAssessment(assessmentId)
    const ids = [...new Set([...cached.visualSources.map((item) => item.id), source.id])]
    const request: ReplaceVisualReferencesRequest = {
      pictureIds: ids,
      version: this.versions.get(assessmentId) ?? 1,
    }
    await this.request<VisualReference[], ReplaceVisualReferencesRequest>(
      `/api/history/v1/assessments/${assessmentId}/visual-references`,
      'PUT',
      request,
    )
    const refreshed = await this.getAssessment(assessmentId)
    const attached = refreshed.visualSources.find((item) => item.id === source.id)
    if (!attached) throw new Error('未能读取已关联的图片，请重新打开本次记录')
    return attached
  }

  async uploadUserPicture(assessmentId: string, filePath: string): Promise<VisualSource> {
    const size = await localImageSize(filePath)
    const picture = await this.uploadPicture(assessmentId, filePath, 'USER_UPLOAD')
    const source = await this.pictureToVisual(picture, assessmentId, undefined, { pictureUrl: filePath, ...size })
    return this.attachVisual(assessmentId, source)
  }

  async addVisualSource(input: AddVisualSourceInput): Promise<VisualSource> {
    if (input.type === 'STANDARD_IMAGE') {
      const side = this.assessmentCache.get(input.assessmentId)?.input.handSide ?? 'RIGHT'
      const view = input.standardView === 'LATERAL' ? 'SIDE' : input.standardView ?? 'PALMAR'
      const pictures = await this.request<PictureResponse[]>(
        `/api/image/v1/standard-pictures?handSide=${side}&view=${view}`,
      )
      const picture = pictures[0]
      if (!picture) throw new Error('当前标准视图不可用')
      const source = await this.pictureToVisual(
        picture,
        input.assessmentId,
        input.standardView,
        { pictureUrl: input.pictureUrl, width: input.width, height: input.height },
      )
      return this.attachVisual(input.assessmentId, source)
    }

    const viewState: ViewState3D | undefined = input.type === 'THREE_D_SNAPSHOT' && input.viewState ? {
      ...input.viewState,
      coarseRegionId: input.viewState.coarseRegionId ?? null,
    } : undefined
    const picture = await this.uploadPicture(
      input.assessmentId,
      input.pictureUrl,
      toApiSourceType(input.type),
      viewState,
    )
    const source = await this.pictureToVisual(
      picture,
      input.assessmentId,
      undefined,
      { pictureUrl: input.pictureUrl, width: input.width, height: input.height },
    )
    return this.attachVisual(input.assessmentId, source)
  }

  async saveAnnotations(sourceId: string, annotations: PictureAnnotation[], description = ''): Promise<VisualSource> {
    const source = this.visualSourceCache.get(sourceId)
    if (!source) throw new Error('请重新打开本次记录后再保存标注')
    const key = `${source.assessmentId}:${sourceId}`
    const request: ReplaceAnnotationSetRequest = {
      assessmentId: source.assessmentId,
      sourceWidth: Math.max(1, source.width ?? 1),
      sourceHeight: Math.max(1, source.height ?? 1),
      shapes: this.toAnnotationShapes(source, annotations),
      note: description || null,
      version: this.annotationVersions.get(key) ?? 0,
    }
    const annotationSet = await this.request<AnnotationSetResponse, ReplaceAnnotationSetRequest>(
      `/api/image/v1/pictures/${sourceId}/annotation-set`,
      'PUT',
      request,
    )
    this.annotationVersions.set(key, annotationSet.version)
    const updated: VisualSource = {
      ...source,
      annotations: this.toPictureAnnotations(annotationSet),
      description: annotationSet.note ?? undefined,
      width: annotationSet.sourceWidth,
      height: annotationSet.sourceHeight,
    }
    this.visualSourceCache.set(sourceId, updated)
    // The first annotation save creates its ID in image-service; refresh the
    // History reference too. Repeat on later saves so a failed History sync can
    // be retried without recreating the already-persisted annotation set.
    return this.attachVisual(source.assessmentId, updated)
  }

  private questionCode(key: string): DomainAgentQuestion['code'] {
    if (/conflict|contradiction|confirm/i.test(key)) return 'CONFIRM_HAND_CONFLICT'
    if (/swell|numb/i.test(key)) return 'SWELLING_OR_NUMBNESS'
    if (/move|press|bend/i.test(key)) return 'MOVEMENT_OR_PRESSURE'
    return 'SYMPTOM_QUALITY'
  }

  private toAgentSession(response: AgentSessionResponse, previous?: AgentSession): AgentSession {
    this.agentResponses.set(response.sessionId, response)
    // Only retain the lookup ID locally. Symptoms/answers remain server-owned.
    uni.setStorageSync(`agent-session:${response.assessmentId}`, response.sessionId)
    const exchanges = previous ? [...previous.exchanges] : []
    if (response.question && !exchanges.some((exchange) => exchange.id === response.question!.questionId)) {
      exchanges.push({
        id: response.question.questionId,
        question: {
          code: this.questionCode(response.question.questionKey),
          prompt: response.question.prompt,
          hint: response.question.helpText ?? undefined,
          options: response.question.options ?? (response.question.answerType === 'BOOLEAN'
            ? [{ value: 'true', label: '是' }, { value: 'false', label: '否' }]
            : undefined),
          allowText: response.question.answerType === 'TEXT' || !response.question.options?.length,
          correctionRequired: response.behavior === 'CLARIFY_CONTRADICTION',
        },
        createdAt: response.updatedAt,
      })
    }
    const session: AgentSession = {
      id: response.sessionId,
      assessmentId: response.assessmentId,
      status: response.status === 'COMPLETED' ? 'COMPLETED' : 'ACTIVE',
      exchanges,
      updatedAt: response.updatedAt,
    }
    this.agentCache.set(session.id, session)
    return session
  }

  /** One explicit advancement only; never poll or silently switch to Mock. */
  private async settleAgentResponse(response: AgentSessionResponse, previous?: AgentSession, advance = true): Promise<AgentSession> {
    this.agentResponses.set(response.sessionId, response)
    uni.setStorageSync(`agent-session:${response.assessmentId}`, response.sessionId)
    let current = response
    if (current.status === 'CREATED' && !advance) return this.toAgentSession(current, previous)
    if (current.status === 'CREATED') {
      current = await this.request<AgentSessionResponse, { expectedVersion: number }>(
        `/api/ai/v1/agent-sessions/${current.sessionId}/advance`,
        'POST',
        { expectedVersion: current.version },
        undefined,
        150_000,
      )
      this.agentResponses.set(current.sessionId, current)
    }
    if (current.status === 'COMPLETED') {
      // Only navigate to the result page after History has accepted the result.
      await this.request<AgentFinalizationResponse>(
        `/api/ai/v1/agent-sessions/${current.sessionId}/finalize`, 'POST',
      )
    } else if (current.status === 'PAUSED') {
      throw new Error('本次分析已暂停，需要显式恢复后继续')
    } else if (current.status === 'CANCELLED') {
      throw new Error('本次分析已取消')
    } else if (current.status === 'FAILED') {
      throw new Error(`分析执行失败：${current.terminationReason ?? '请检查服务状态后恢复任务'}`)
    } else if (current.status === 'RUNNING') {
      throw new Error('本次分析正在处理，请稍后重新打开记录查看')
    } else if (current.status !== 'WAITING_FOR_USER' || !current.question) {
      throw new Error('分析服务没有返回可回答的问题，请重新读取任务状态')
    }
    return this.toAgentSession(current, previous)
  }

  async startAgent(assessmentId: string): Promise<AgentSession> {
    // A new device can discover the existing task by Assessment ID. Only a 404
    // lookup permits creation; auth/upstream failures must remain visible.
    const existing = await this.getAgentSession(assessmentId)
    if (existing) return existing
    // Submit persisted input first; an AI error never rolls back a successful History submit.
    const current = await this.request<AssessmentResponse>(`/api/history/v1/assessments/${assessmentId}`)
    const submitted = await this.request<AssessmentResponse, SubmitAssessmentRequest>(
      `/api/history/v1/assessments/${assessmentId}/submit`,
      'POST',
      { version: current.version },
    )
    await this.toAssessment(submitted)

    const request: CreateAgentSessionRequest = { assessmentId, preferredLocale: 'zh-CN' }
    let createKey = uni.getStorageSync(`agent-create-key:${assessmentId}`) as string | undefined
    if (!createKey) {
      createKey = createUuid()
      uni.setStorageSync(`agent-create-key:${assessmentId}`, createKey)
    }
    const response = await this.request<AgentSessionResponse, CreateAgentSessionRequest>(
      '/api/ai/v1/agent-sessions',
      'POST',
      request,
      { 'X-Idempotency-Key': createKey },
    )
    return this.settleAgentResponse(response)
  }

  async getAgentSession(assessmentId: string, advance = true): Promise<AgentSession | undefined> {
    const cached = [...this.agentCache.values()].find((session) => session.assessmentId === assessmentId)
    const stored = uni.getStorageSync(`agent-session:${assessmentId}`) as string | undefined
    if (cached || stored) {
      const current = await this.optional<AgentSessionResponse>(
        `/api/ai/v1/agent-sessions/${cached?.id ?? stored}`,
      )
      if (current) return this.settleAgentResponse(current, cached, advance)
      if (cached) {
        this.agentCache.delete(cached.id)
        this.agentResponses.delete(cached.id)
      }
      uni.removeStorageSync(`agent-session:${assessmentId}`)
      uni.removeStorageSync(`agent-create-key:${assessmentId}`)
    }
    const active = await this.optional<AgentSessionResponse>(
      `/api/ai/v1/agent-sessions/active?assessmentId=${encodeURIComponent(assessmentId)}`,
    )
    return active ? this.settleAgentResponse(active, undefined, advance) : undefined
  }

  async answerAgent(sessionId: string, answer: string): Promise<AgentSession> {
    let previous = this.agentCache.get(sessionId)
    let current = this.agentResponses.get(sessionId)
    if (!previous || !current) {
      current = await this.request<AgentSessionResponse>(`/api/ai/v1/agent-sessions/${sessionId}`)
      previous = this.toAgentSession(current)
    }
    if (!current) throw new Error('请重新读取分析任务后回答')
    if (current.status === 'COMPLETED') return this.settleAgentResponse(current, previous)
    const unanswered = [...previous.exchanges].reverse().find((exchange) => !exchange.answer)
    if (!unanswered) throw new Error('当前没有等待回答的问题')
    let pending = this.pendingAgentAnswers.get(sessionId)
    if (!pending || pending.text !== answer || pending.request.questionId !== unanswered.id) {
      let value: unknown = answer
      if (current.question?.answerType === 'BOOLEAN') {
        if (answer === 'true' || answer === '是') value = true
        else if (answer === 'false' || answer === '否') value = false
        else throw new Error('这个问题请选择是或否')
      } else if (current.question?.answerType === 'MULTI_CHOICE') {
        value = answer.split(/[,，]/).map((item) => item.trim()).filter(Boolean)
      }
      pending = {
        key: createUuid(), text: answer,
        request: {
          questionId: unanswered.id,
          answer: value,
          expectedStepNumber: current.currentStepNumber,
          expectedVersion: current.version,
          baseCheckpointId: current.activeCheckpointId,
        },
      }
      this.pendingAgentAnswers.set(sessionId, pending)
    }
    let response: AgentSessionResponse
    try {
      response = await this.request<AgentSessionResponse, AnswerAgentQuestionRequest>(
        `/api/ai/v1/agent-sessions/${sessionId}/responses`,
        'POST',
        pending.request,
        { 'X-Idempotency-Key': pending.key },
      )
    } catch (error) {
      // Validation/version failures were rejected. Reload before a new intent;
      // uncertain network/5xx failures retain the original operation key/body.
      if (error instanceof HttpStatusError && error.status >= 400 && error.status < 500 && error.status !== 429) {
        this.pendingAgentAnswers.delete(sessionId)
        this.agentResponses.delete(sessionId)
      }
      throw error
    }
    // Mutate the display only after persistence succeeds; a failed request stays retryable.
    const accepted: AgentSession = {
      ...previous,
      exchanges: previous.exchanges.map((exchange) => exchange.id === unanswered.id
        ? { ...exchange, answer } : exchange),
    }
    // The answer is durably accepted even if the subsequent model advance is denied.
    // A read-only refresh must not resurrect the already-answered question.
    this.agentCache.set(sessionId, accepted)
    this.pendingAgentAnswers.delete(sessionId)
    const settled = await this.settleAgentResponse(response, accepted)
    return settled
  }
}
