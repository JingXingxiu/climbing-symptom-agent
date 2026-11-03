<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import StageProgress from '@/components/StageProgress.vue'
import { api } from '@/api'
import { isMockMode, paymentApi } from '@/api/payment'
import { isQuotaExceeded } from '@/api/http-client'
import type { Quota } from '@/types/payment'
import type { AgentExchange, AgentSession, Assessment } from '@/types/domain'
import { go, queryValue } from '@/utils/navigation'
import { formatSymptoms } from '@/utils/symptoms'
import { formatOnset } from '@/utils/onset'

const assessmentId = ref('')
const assessment = ref<Assessment>()
const session = ref<AgentSession>()
const answer = ref('')
const submitting = ref(false)
const loading = ref(false)
const syncingSession = ref(false)
const sessionSyncError = ref('')
const loadError = ref('')
const quota = ref<Quota>()
const quotaError = ref('')
const quotaBlocked = ref(false)

const current = computed<AgentExchange | undefined>(() => session.value?.exchanges.find((item) => !item.answer))
const completed = computed(() => session.value?.status === 'COMPLETED')

async function refreshQuota(): Promise<void> {
  try {
    quota.value = await paymentApi.getQuota()
    quotaError.value = ''
    quotaBlocked.value = quota.value.remainingModelCalls <= 0
  } catch {
    quotaError.value = '额度暂未读取成功，已有记录仍可查看。'
  }
}

function showError(error: unknown, fallback: string): void {
  if (isQuotaExceeded(error)) {
    quotaBlocked.value = true
    loadError.value = '今日模型额度已用完或调用受到限制。请查看套餐与额度后，手动继续。'
    return
  }
  loadError.value = error instanceof Error ? error.message : fallback
}

async function initialize(): Promise<void> {
  if (loading.value || submitting.value || syncingSession.value) return
  loading.value = true
  loadError.value = ''
  try {
    await refreshQuota()
    assessment.value = await api.getAssessment(assessmentId.value)
    // Reading a CREATED session must not silently retry a previously denied execution.
    session.value = await api.getAgentSession(assessmentId.value, false)
    if (!session.value && !quotaBlocked.value) session.value = await api.startAgent(assessmentId.value)
    if (session.value?.status === 'COMPLETED') go('/pages/result/index', { id: assessmentId.value })
  } catch (error) {
    showError(error, '加载失败')
    if (assessment.value) await syncSessionReadOnly()
  } finally { await refreshQuota(); loading.value = false }
}

onLoad((options) => {
  assessmentId.value = queryValue(options?.id)
  void initialize()
})

async function syncSessionReadOnly(): Promise<void> {
  const previousQuestionId = current.value?.id
  try {
    const latest = await api.getAgentSession(assessmentId.value, false)
    session.value = latest
    sessionSyncError.value = ''
    if (current.value?.id !== previousQuestionId) answer.value = ''
    if (latest?.status === 'COMPLETED') go('/pages/result/index', { id: assessmentId.value })
  } catch {
    // Keep the original submission failure visible and prevent reuse of stale questions.
    sessionSyncError.value = '任务状态尚未确认，请重新读取后继续。'
  }
}

async function refreshSessionView(): Promise<void> {
  if (!assessment.value || loading.value || submitting.value || syncingSession.value) return
  syncingSession.value = true
  try { await Promise.all([refreshQuota(), syncSessionReadOnly()]) }
  finally { syncingSession.value = false }
}

// Returning from plans synchronizes display only; it never advances or resends an answer.
onShow(() => { void refreshSessionView() })

async function continueAgent(): Promise<void> {
  if (loading.value || submitting.value || syncingSession.value || sessionSyncError.value || quotaBlocked.value) return
  submitting.value = true
  loadError.value = ''
  try {
    session.value = await api.startAgent(assessmentId.value)
    if (session.value.status === 'COMPLETED') go('/pages/result/index', { id: assessmentId.value })
  } catch (error) {
    showError(error, '继续分析失败')
    await syncSessionReadOnly()
  } finally { await refreshQuota(); submitting.value = false }
}

async function submit(value?: string): Promise<void> {
  const finalAnswer = (value ?? answer.value).trim()
  if (!session.value || !finalAnswer || submitting.value || loading.value || syncingSession.value) return
  if (finalAnswer === 'EDIT') {
    go('/pages/assessment/location', { id: assessmentId.value })
    return
  }
  if (quotaBlocked.value || sessionSyncError.value) return
  submitting.value = true
  loadError.value = ''
  try {
    session.value = await api.answerAgent(session.value.id, finalAnswer)
    answer.value = ''
    if (session.value.status === 'COMPLETED') {
      uni.showToast({ title: '信息整理完成', icon: 'success' })
      setTimeout(() => go('/pages/result/index', { id: assessmentId.value }), 350)
    }
  } catch (error) {
    showError(error, '提交失败')
    await syncSessionReadOnly()
  } finally {
    await refreshQuota()
    submitting.value = false
  }
}
</script>

<template>
  <view class="page">
    <StageProgress :current="3" />
    <view class="hero hero--compact">
      <text class="eyebrow">STEP 03 · {{ isMockMode ? 'MOCK AGENT' : 'AGENT' }}</text>
      <text class="title title--small">再确认几个关键点</text>
      <text class="subtitle">问题数量有限。演示会根据当前信息继续缩小范围，或在发现矛盾时请你修正。</text>
    </view>

    <view class="quota-strip">
      <view>
        <text v-if="quota" class="quota-strip__title">今日剩余 {{ quota.remainingModelCalls }} / {{ quota.dailyModelCallLimit }} 次</text>
        <text v-else class="quota-strip__title">今日额度待查询</text>
        <text class="quota-strip__hint">模型执行尝试计次 · 每个会话最多 20 次</text>
        <text v-if="quotaError" class="quota-strip__hint" @tap="refreshQuota">{{ quotaError }} 点击重试</text>
      </view>
      <text class="quota-strip__link" @tap="go('/pages/payment/index')">套餐与额度 →</text>
    </view>
    <view v-if="loading" class="notice agent-alert">正在读取记录…</view>
    <view v-if="loadError || quotaBlocked" class="notice agent-alert">
      <text>{{ loadError || '今日模型执行次数已用完。已有内容可以继续查看。' }}</text>
      <button v-if="quotaBlocked" class="secondary-button retry-button" @tap="go('/pages/payment/index')">查看套餐与额度</button>
      <button v-else-if="!assessment" class="secondary-button retry-button" :disabled="loading || submitting" @tap="initialize">重新加载记录</button>
    </view>
    <view v-if="sessionSyncError" class="notice agent-alert">
      <text>{{ sessionSyncError }}</text>
      <button class="secondary-button retry-button" :disabled="loading || submitting || syncingSession" @tap="refreshSessionView">重新读取任务状态</button>
    </view>

    <view v-if="assessment" class="context-card">
      <view class="context-card__row">
        <text>{{ assessment.input.handSide === 'LEFT' ? '左手' : '右手' }}</text>
        <text>{{ formatSymptoms(assessment.input.symptomTypes) }}</text>
        <text>{{ assessment.symptomLocations[0]?.displayLabel || '已标记位置' }}</text>
        <text>{{ formatOnset(assessment.input) }}</text>
      </view>
      <text class="context-card__trigger">{{ assessment.input.trigger }}</text>
    </view>

    <view class="conversation">
      <view v-for="exchange in session?.exchanges" :key="exchange.id" class="exchange">
        <view :class="['agent-bubble', { 'agent-bubble--warning': exchange.question.correctionRequired }]">
          <text class="agent-bubble__name">辅助分析</text>
          <text class="agent-bubble__text">{{ exchange.question.prompt }}</text>
          <text v-if="exchange.question.hint" class="agent-bubble__hint">{{ exchange.question.hint }}</text>
        </view>
        <view v-if="exchange.answer" class="user-bubble">{{ exchange.answer }}</view>
      </view>
    </view>

    <view v-if="current && !completed" class="answer-panel">
      <view v-if="current.question.options?.length" class="answer-options">
        <button
          v-for="option in current.question.options"
          :key="option.value"
          class="answer-option"
          :disabled="submitting || loading || syncingSession || ((quotaBlocked || Boolean(sessionSyncError)) && option.value !== 'EDIT')"
          @tap="submit(option.value)"
        >{{ option.label }}</button>
      </view>
      <view v-if="current.question.allowText" class="custom-answer">
        <textarea v-model="answer" class="textarea custom-answer__input" maxlength="160" placeholder="也可以用自己的话回答…" />
        <button class="primary-button" :disabled="!answer.trim() || quotaBlocked || loading || submitting || syncingSession || Boolean(sessionSyncError)" :loading="submitting" @tap="submit()">提交回答</button>
      </view>
    </view>

    <button v-if="assessment && !current && !completed && !loading" class="primary-button" :disabled="submitting || quotaBlocked || syncingSession || Boolean(sessionSyncError)" :loading="submitting" @tap="continueAgent">手动继续分析</button>

    <view class="agent-note">
      <text class="agent-note__title">{{ isMockMode ? '当前为确定性 Mock 流程' : '辅助信息整理' }}</text>
      <text>{{ isMockMode ? '不会调用真实模型。演示推进会计入本地模型执行用量，用于体验每日额度。' : '模型执行尝试会计入每日用量，发生调用失败时也可能计次。' }}本工具不提供医疗诊断。</text>
    </view>
  </view>
</template>

<style scoped lang="scss">
.quota-strip { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; margin-bottom: 24rpx; padding: 22rpx; border-radius: 20rpx; background: #e1eee8; }
.quota-strip__title { display: block; color: #255449; font-size: 26rpx; font-weight: 700; }
.quota-strip__hint { display: block; margin-top: 6rpx; color: #657c72; font-size: 20rpx; }
.quota-strip__link { flex-shrink: 0; color: var(--primary); font-size: 22rpx; font-weight: 650; }
.agent-alert { margin-bottom: 24rpx; }
.retry-button { min-height: 72rpx; margin-top: 16rpx; font-size: 24rpx; }
.hero--compact { padding-top: 8rpx; }
.title--small { font-size: 41rpx; }
.context-card { margin-bottom: 28rpx; padding: 22rpx 25rpx; border-radius: 21rpx; background: #1d3e38; color: #fff; }
.context-card__row { display: flex; gap: 12rpx; color: #cce2dc; font-size: 22rpx; }
.context-card__row text { padding-right: 12rpx; border-right: 1rpx solid rgba(255,255,255,.25); }
.context-card__row text:last-child { border: 0; }
.context-card__trigger { display: block; margin-top: 13rpx; font-size: 26rpx; font-weight: 650; }
.conversation { padding: 4rpx 0 12rpx; }
.exchange { display: flex; flex-direction: column; margin-bottom: 22rpx; }
.agent-bubble { max-width: 88%; padding: 25rpx 27rpx; border-radius: 8rpx 25rpx 25rpx 25rpx; background: #fff; box-shadow: var(--shadow); }
.agent-bubble--warning { border-left: 7rpx solid #d58a24; background: #fffaf0; }
.agent-bubble__name { display: block; margin-bottom: 9rpx; color: var(--primary); font-size: 21rpx; font-weight: 750; }
.agent-bubble__text { display: block; color: #26312f; font-size: 28rpx; line-height: 1.62; }
.agent-bubble__hint { display: block; margin-top: 11rpx; color: var(--muted); font-size: 22rpx; }
.user-bubble { max-width: 80%; align-self: flex-end; margin-top: 13rpx; padding: 19rpx 24rpx; border-radius: 23rpx 8rpx 23rpx 23rpx; background: var(--primary); color: #fff; font-size: 25rpx; line-height: 1.5; }
.answer-panel { margin-top: 8rpx; padding: 25rpx; border: 1rpx solid var(--line); border-radius: 25rpx; background: #fff; }
.answer-options { display: grid; grid-template-columns: 1fr 1fr; gap: 13rpx; }
.answer-option { min-height: 77rpx; margin: 0; padding: 12rpx 15rpx; border: 1rpx solid var(--line); border-radius: 18rpx; background: #f8faf9; color: #34433f; font-size: 24rpx; line-height: 1.3; }
.custom-answer { margin-top: 17rpx; }
.custom-answer__input { min-height: 115rpx; margin-bottom: 15rpx; }
.agent-note { margin-top: 27rpx; padding: 22rpx; border-radius: 19rpx; background: #e7ecea; color: #65736f; font-size: 22rpx; line-height: 1.55; }
.agent-note__title { display: block; margin-bottom: 5rpx; color: #40504c; font-weight: 700; }
</style>
