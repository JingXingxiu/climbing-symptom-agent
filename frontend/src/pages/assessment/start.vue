<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import StageProgress from '@/components/StageProgress.vue'
import { api } from '@/api'
import type { Assessment, AssessmentInput, SymptomType } from '@/types/domain'
import { go, queryValue } from '@/utils/navigation'
import { SYMPTOM_OPTIONS } from '@/utils/symptoms'

const assessment = ref<Assessment>()
const saving = ref(false)
const form = reactive<AssessmentInput>({
  handSide: 'RIGHT',
  symptomTypes: [],
  onsetKind: 'UNKNOWN',
  onsetText: '',
  trigger: '',
  normalUseImpact: 'NONE',
  additionalDescription: '',
})

const onsetOptions = [
  { label: '今天', kind: 'TODAY', amount: undefined },
  { label: '2～3 天前', kind: 'DAYS_AGO', amount: 3 },
  { label: '一周左右', kind: 'WEEKS_AGO', amount: 1 },
  { label: '一个月内', kind: 'WEEKS_AGO', amount: 4 },
  { label: '超过一个月', kind: 'MONTHS_AGO', amount: 2 },
  { label: '记不清', kind: 'UNKNOWN', amount: undefined },
] as const
const onsetLabels = onsetOptions.map((item) => item.label)
const triggerSuggestions = ['抓小点', '扣指', '捏握', '拉门把手', '弯曲手指', '按压时', '休息时也明显']
const symptomOptions = SYMPTOM_OPTIONS
const impactOptions = [
  { value: 'NONE', label: '不影响' },
  { value: 'MILD', label: '有点影响' },
  { value: 'SIGNIFICANT', label: '明显影响' },
] as const

const valid = computed(() => Boolean(form.symptomTypes.length && form.onsetText?.trim() && form.trigger.trim()))

function hydrate(item: Assessment): void {
  assessment.value = item
  Object.assign(form, item.input)
}

onLoad(async (options) => {
  try {
    const id = queryValue(options?.id)
    hydrate(id ? await api.getAssessment(id) : await api.createAssessment())
  } catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '加载失败', icon: 'none' })
  }
})

function addTrigger(value: string): void {
  form.trigger = form.trigger.includes(value) ? form.trigger : `${form.trigger}${form.trigger ? '、' : ''}${value}`
}

function toggleSymptom(value: SymptomType): void {
  const index = form.symptomTypes.indexOf(value)
  if (index >= 0) form.symptomTypes.splice(index, 1)
  else form.symptomTypes.push(value)
}

function selectOnset(index: number): void {
  const option = onsetOptions[index]
  if (!option) return
  form.onsetKind = option.kind
  form.onsetAmount = option.amount
  form.onsetDate = undefined
  form.onsetText = option.label
}

function confirmHandReset(): Promise<boolean> {
  return new Promise((resolve) => {
    uni.showModal({
      title: '更换不适手别？',
      content: '已有的粗位置和视觉资料关联将被清空，需要按新手别重新标记。',
      confirmText: '更换并清空',
      success: (result) => resolve(result.confirm),
      fail: () => resolve(false),
    })
  })
}

async function save(next = false): Promise<void> {
  if (!assessment.value || saving.value) return
  if (next && !valid.value) {
    uni.showToast({ title: '请选择症状，并补充时间和最明显的情况', icon: 'none' })
    return
  }
  saving.value = true
  try {
    const handChanged = assessment.value.input.handSide !== form.handSide
    const hasLocationData = Boolean(assessment.value.symptomLocations.length || assessment.value.visualSources.length)
    if (handChanged && hasLocationData && !await confirmHandReset()) return
    if (handChanged) {
      assessment.value.symptomLocations = []
      assessment.value.visualSources = []
    }
    assessment.value.input = { ...form }
    assessment.value.currentStepKey = next ? 'LOCATION_3D' : 'BASIC_INPUT'
    assessment.value.status = 'DRAFT'
    assessment.value = await api.saveAssessment(assessment.value)
    if (next) go('/pages/assessment/location', { id: assessment.value.id })
    else uni.showToast({ title: '草稿已保存', icon: 'success' })
  } catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '保存失败', icon: 'none' })
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <view class="page">
    <StageProgress :current="1" />
    <view class="hero hero--compact">
      <text class="eyebrow">STEP 01</text>
      <text class="title title--small">先说最必要的情况</text>
      <text class="subtitle">保持简短即可，更多细节稍后按需追问。</text>
    </view>

    <view class="card">
      <view class="field field--first">
        <text class="label required">哪只手有不适？</text>
        <view class="segmented">
          <view :class="['segment', { 'segment--active': form.handSide === 'LEFT' }]" @tap="form.handSide = 'LEFT'">左手</view>
          <view :class="['segment', { 'segment--active': form.handSide === 'RIGHT' }]" @tap="form.handSide = 'RIGHT'">右手</view>
        </view>
      </view>

      <view class="field">
        <text class="label required">主要有哪些症状？<text class="label-hint">可多选</text></text>
        <view class="chip-row symptom-row">
          <text
            v-for="item in symptomOptions"
            :key="item.value"
            :class="['chip symptom-chip', { 'chip--active': form.symptomTypes.includes(item.value) }]"
            @tap="toggleSymptom(item.value)"
          >{{ form.symptomTypes.includes(item.value) ? '✓ ' : '' }}{{ item.label }}</text>
        </view>
      </view>

      <view class="field">
        <text class="label required">大概什么时候开始？</text>
        <picker :range="onsetLabels" @change="selectOnset(Number($event.detail.value))">
          <view :class="['picker-value', { 'picker-value--placeholder': !form.onsetText }]">
            {{ form.onsetText || '请选择大概时间' }}
          </view>
        </picker>
      </view>

      <view class="field">
        <text class="label required">什么动作或情况下最明显？</text>
        <textarea v-model="form.trigger" class="textarea textarea--short" maxlength="120" placeholder="例如：攀岩抓小点、手指弯曲发力时" />
        <view class="chip-row suggestion-row">
          <text v-for="item in triggerSuggestions" :key="item" class="chip" @tap="addTrigger(item)">+ {{ item }}</text>
        </view>
      </view>

      <view class="field">
        <text class="label required">是否影响正常使用？</text>
        <view class="segmented">
          <view
            v-for="item in impactOptions"
            :key="item.value"
            :class="['segment', { 'segment--active': form.normalUseImpact === item.value }]"
            @tap="form.normalUseImpact = item.value"
          >{{ item.label }}</view>
        </view>
      </view>

      <view class="field">
        <text class="label">其他想补充的（选填）</text>
        <textarea
          v-model="form.additionalDescription"
          class="textarea"
          maxlength="300"
          placeholder="例如具体感觉、变化过程，或其他没列出的不适。说不清也可以留空。"
        />
        <text class="counter">{{ form.additionalDescription.length }}/300</text>
      </view>
    </view>

    <view class="sticky-actions">
      <button class="secondary-button" :loading="saving" @tap="save(false)">保存草稿</button>
      <button class="primary-button" :disabled="!valid" :loading="saving" @tap="save(true)">下一步：标记位置</button>
    </view>
  </view>
</template>

<style scoped lang="scss">
.hero--compact { padding-top: 8rpx; }
.title--small { font-size: 41rpx; }
.field--first { margin-top: 0; }
.textarea--short { min-height: 130rpx; }
.suggestion-row { margin-top: 16rpx; }
.suggestion-row .chip { padding: 12rpx 17rpx; font-size: 22rpx; }
.label-hint { margin-left: 10rpx; color: var(--muted); font-size: 21rpx; font-weight: 400; }
.symptom-row { gap: 12rpx; }
.symptom-chip { min-width: 112rpx; text-align: center; }
.picker-value--placeholder { color: #9aa5a2; }
.counter { display: block; margin-top: 8rpx; color: #9aa5a2; font-size: 21rpx; text-align: right; }
</style>
