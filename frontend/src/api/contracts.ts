import type {
  AgentSession,
  Assessment,
  AssessmentInput,
  PictureAnnotation,
  VisualSource,
  VisualSourceType,
} from '@/types/domain'

export interface AddVisualSourceInput {
  assessmentId: string
  type: VisualSourceType
  pictureUrl: string
  previewUrl?: string
  width?: number
  height?: number
  standardView?: VisualSource['standardView']
  uploadStatus?: VisualSource['uploadStatus']
  viewState?: VisualSource['viewState']
}

export interface AssessmentApi {
  bootstrap(): Promise<void>
  createAssessment(input?: Partial<AssessmentInput>): Promise<Assessment>
  getAssessment(id: string): Promise<Assessment>
  saveAssessment(assessment: Assessment): Promise<Assessment>
  listAssessments(): Promise<Assessment[]>
  uploadUserPicture(assessmentId: string, filePath: string): Promise<VisualSource>
  addVisualSource(input: AddVisualSourceInput): Promise<VisualSource>
  saveAnnotations(sourceId: string, annotations: PictureAnnotation[], description?: string): Promise<VisualSource>
  startAgent(assessmentId: string): Promise<AgentSession>
  getAgentSession(assessmentId: string, advance?: boolean): Promise<AgentSession | undefined>
  answerAgent(sessionId: string, answer: string): Promise<AgentSession>
}
