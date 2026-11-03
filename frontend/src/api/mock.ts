import type { AddVisualSourceInput, AssessmentApi } from './contracts'
import type {
  AgentQuestion,
  AgentSession,
  Assessment,
  AssessmentInput,
  PictureAnnotation,
  VisualSource,
} from '@/types/domain'
import { createId } from '@/utils/id'
import { formatSymptoms } from '@/utils/symptoms'
import { consumeMockModelCall } from './payment-mock'

const STORAGE_KEY = 'climbing-symptom-mock-state-v3'

interface MockState {
  assessments: Assessment[]
  sessions: AgentSession[]
}

const defaultInput: AssessmentInput = {
  handSide: 'RIGHT',
  symptomTypes: [],
  onsetKind: 'UNKNOWN',
  onsetText: '',
  trigger: '',
  normalUseImpact: 'NONE',
  additionalDescription: '',
}

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

function now(): string {
  return new Date().toISOString()
}

function persistDemoPicture(filePath: string): Promise<string> {
  return new Promise((resolve) => {
    uni.saveFile({
      tempFilePath: filePath,
      success: (result) => resolve(result.savedFilePath),
      // Simulator/H5 and already-persistent paths can reject saveFile. Keeping
      // the original path still lets the current session demonstrate the flow.
      fail: () => resolve(filePath),
    })
  })
}

function firstQuestion(assessment: Assessment): AgentQuestion {
  const text = assessment.input.additionalDescription
  const conflicts = (assessment.input.handSide === 'LEFT' && /右手|右侧/.test(text))
    || (assessment.input.handSide === 'RIGHT' && /左手|左侧/.test(text))

  if (conflicts) {
    return {
      code: 'CONFIRM_HAND_CONFLICT',
      prompt: `你选择的是${assessment.input.handSide === 'LEFT' ? '左手' : '右手'}，但补充描述里似乎提到了另一只手。请确认不适的是哪只手。`,
      options: [
        { label: '以页面选择为准', value: assessment.input.handSide },
        { label: '我需要返回修改', value: 'EDIT' },
      ],
      allowText: true,
      correctionRequired: true,
    }
  }

  return {
    code: 'SYMPTOM_QUALITY',
    prompt: '这种不适还有更具体的感觉吗？',
    hint: '不确定也没关系，可以用自己的话描述。',
    options: [
      { label: '刺痛', value: '刺痛' },
      { label: '灼热', value: '灼热' },
      { label: '触电感', value: '触电感' },
      { label: '说不清', value: '说不清' },
    ],
    allowText: true,
  }
}

function nextQuestion(code: AgentQuestion['code']): AgentQuestion | undefined {
  if (code === 'CONFIRM_HAND_CONFLICT') {
    return {
      code: 'SYMPTOM_QUALITY',
      prompt: '收到。现在这种不适还有更具体的感觉吗？',
      options: [
        { label: '刺痛', value: '刺痛' },
        { label: '灼热', value: '灼热' },
        { label: '触电感', value: '触电感' },
        { label: '说不清', value: '说不清' },
      ],
      allowText: true,
    }
  }
  if (code === 'SYMPTOM_QUALITY') {
    return {
      code: 'SWELLING_OR_NUMBNESS',
      prompt: '目前有明显肿胀或麻木吗？',
      options: [
        { label: '都没有', value: '没有肿胀或麻木' },
        { label: '有肿胀', value: '有肿胀' },
        { label: '有麻木', value: '有麻木' },
        { label: '两者都有', value: '有肿胀和麻木' },
      ],
      allowText: true,
    }
  }
  if (code === 'SWELLING_OR_NUMBNESS') {
    return {
      code: 'MOVEMENT_OR_PRESSURE',
      prompt: '弯曲手指和按压这个位置，哪一种更容易让不适变明显？',
      options: [
        { label: '弯曲更明显', value: '弯曲时更明显' },
        { label: '按压更明显', value: '按压时更明显' },
        { label: '都会', value: '弯曲和按压都会' },
        { label: '都不明显', value: '弯曲和按压都不明显' },
      ],
      allowText: true,
    }
  }
  return undefined
}

export class MockAssessmentApi implements AssessmentApi {
  private state: MockState = { assessments: [], sessions: [] }

  async bootstrap(): Promise<void> {
    const cached = uni.getStorageSync(STORAGE_KEY) as string | MockState | undefined
    if (!cached) return
    try {
      const parsed = typeof cached === 'string' ? JSON.parse(cached) as MockState : cached
      this.state = {
        ...parsed,
        assessments: (parsed.assessments ?? []).map((assessment) => ({
          ...assessment,
          input: {
            ...defaultInput,
            ...assessment.input,
            symptomTypes: Array.isArray(assessment.input?.symptomTypes) ? assessment.input.symptomTypes : [],
          },
        })),
      }
      this.persist()
    } catch {
      uni.removeStorageSync(STORAGE_KEY)
    }
  }

  private persist(): void {
    uni.setStorageSync(STORAGE_KEY, JSON.stringify(this.state))
  }

  async createAssessment(input: Partial<AssessmentInput> = {}): Promise<Assessment> {
    const timestamp = now()
    const assessment: Assessment = {
      id: createId('assessment'),
      status: 'DRAFT',
      currentStepKey: 'BASIC_INPUT',
      input: { ...defaultInput, ...input },
      symptomLocations: [],
      visualSources: [],
      createdAt: timestamp,
      updatedAt: timestamp,
    }
    this.state.assessments.unshift(assessment)
    this.persist()
    return clone(assessment)
  }

  async getAssessment(id: string): Promise<Assessment> {
    const item = this.state.assessments.find((candidate) => candidate.id === id)
    if (!item) throw new Error('没有找到这条记录')
    return clone(item)
  }

  async saveAssessment(assessment: Assessment): Promise<Assessment> {
    const index = this.state.assessments.findIndex((candidate) => candidate.id === assessment.id)
    if (index < 0) throw new Error('记录不存在或已被删除')
    const updated = { ...clone(assessment), updatedAt: now() }
    this.state.assessments.splice(index, 1, updated)
    this.persist()
    return clone(updated)
  }

  async listAssessments(): Promise<Assessment[]> {
    return clone([...this.state.assessments].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)))
  }

  async uploadUserPicture(assessmentId: string, filePath: string): Promise<VisualSource> {
    await new Promise<void>((resolve) => setTimeout(resolve, 240))
    const persistedPath = await persistDemoPicture(filePath)
    return this.addVisualSource({
      assessmentId,
      type: 'USER_IMAGE',
      pictureUrl: persistedPath,
      previewUrl: persistedPath,
      uploadStatus: 'READY',
    })
  }

  async addVisualSource(input: AddVisualSourceInput): Promise<VisualSource> {
    const assessment = this.state.assessments.find((candidate) => candidate.id === input.assessmentId)
    if (!assessment) throw new Error('记录不存在')
    const source: VisualSource = {
      id: createId('visual'),
      assessmentId: input.assessmentId,
      type: input.type,
      pictureUrl: input.pictureUrl,
      previewUrl: input.previewUrl,
      width: input.width,
      height: input.height,
      standardView: input.standardView,
      uploadStatus: input.uploadStatus ?? 'READY',
      viewState: input.viewState,
      annotations: [],
      createdAt: now(),
    }
    assessment.visualSources.push(source)
    assessment.updatedAt = now()
    this.persist()
    return clone(source)
  }

  async saveAnnotations(sourceId: string, annotations: PictureAnnotation[], description = ''): Promise<VisualSource> {
    for (const assessment of this.state.assessments) {
      const source = assessment.visualSources.find((candidate) => candidate.id === sourceId)
      if (source) {
        source.annotations = clone(annotations)
        source.description = description
        assessment.updatedAt = now()
        this.persist()
        return clone(source)
      }
    }
    throw new Error('视觉资料不存在')
  }

  async startAgent(assessmentId: string): Promise<AgentSession> {
    const existing = this.state.sessions.find((candidate) => candidate.assessmentId === assessmentId)
    if (existing) return clone(existing)
    const assessment = this.state.assessments.find((candidate) => candidate.id === assessmentId)
    if (!assessment) throw new Error('记录不存在')
    consumeMockModelCall()
    const session: AgentSession = {
      id: createId('agent'),
      assessmentId,
      status: 'ACTIVE',
      exchanges: [{ id: createId('step'), question: firstQuestion(assessment), createdAt: now() }],
      updatedAt: now(),
    }
    assessment.status = 'IN_PROGRESS'
    assessment.currentStepKey = 'AGENT'
    assessment.updatedAt = now()
    this.state.sessions.push(session)
    this.persist()
    return clone(session)
  }

  async getAgentSession(assessmentId: string): Promise<AgentSession | undefined> {
    const session = this.state.sessions.find((candidate) => candidate.assessmentId === assessmentId)
    return session ? clone(session) : undefined
  }

  async answerAgent(sessionId: string, answer: string): Promise<AgentSession> {
    const session = this.state.sessions.find((candidate) => candidate.id === sessionId)
    if (!session || session.status !== 'ACTIVE') throw new Error('当前辅助问答不可继续')
    const current = session.exchanges.at(-1)
    if (!current) throw new Error('问题状态异常')
    if (session.exchanges.length >= 20) throw new Error('本次问答已达到 20 次执行上限，请查看已有记录。')
    consumeMockModelCall()
    current.answer = answer
    const next = nextQuestion(current.question.code)
    if (next) {
      session.exchanges.push({ id: createId('step'), question: next, createdAt: now() })
    } else {
      session.status = 'COMPLETED'
      const assessment = this.state.assessments.find((candidate) => candidate.id === session.assessmentId)
      if (assessment) {
        const answers = session.exchanges.map((item) => item.answer ?? '').join('；')
        const requiresAttention = assessment.input.normalUseImpact === 'SIGNIFICANT'
          || assessment.input.symptomTypes.some((type) => ['NUMBNESS', 'SWELLING', 'WEAKNESS'].includes(type))
          || /麻木|明显肿胀/.test(answers)
        const handLabel = assessment.input.handSide === 'LEFT' ? '左手' : '右手'
        const location = assessment.symptomLocations[0]?.displayLabel ?? `${handLabel}已标记的位置`
        const symptomSummary = formatSymptoms(assessment.input.symptomTypes)
        assessment.result = {
          assessmentId: assessment.id,
          summary: `${location}出现${symptomSummary}，${assessment.input.trigger}时较明显。补充情况：${answers || '暂无'}。`,
          recommendationLevel: requiresAttention ? 'ROUTINE_PROFESSIONAL' : 'SELF_MONITOR',
          reasons: requiresAttention
            ? ['当前描述包含功能明显受影响、麻木、肿胀或无力等需要关注的表现。']
            : ['当前信息中暂未发现需要立即处理的明显警示表现。'],
          suggestedNextActions: requiresAttention
            ? ['减少诱发动作，并携带本次记录咨询专业人员。']
            : ['减少诱发动作并观察变化。', '如果持续加重或影响正常使用，请寻求专业帮助。'],
          warningSigns: ['严重外伤、明显畸形、症状快速加重或手部功能突然下降时，请及时寻求专业帮助。'],
          safetyNotice: '本结果仅用于帮助整理个人描述，不提供疾病诊断，也不能替代专业医疗意见。',
          generatedBy: 'MOCK',
          finalizedAt: now(),
        }
        assessment.status = 'COMPLETED'
        assessment.currentStepKey = 'RESULT'
        assessment.updatedAt = now()
      }
    }
    session.updatedAt = now()
    this.persist()
    return clone(session)
  }
}
