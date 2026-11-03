<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import StageProgress from '@/components/StageProgress.vue'
import { api } from '@/api'
import type { Assessment } from '@/types/domain'
import { go, queryValue } from '@/utils/navigation'
import { formatSymptoms } from '@/utils/symptoms'
import { formatOnset } from '@/utils/onset'

const assessment = ref<Assessment>()
function copySource(url?: string | null) {
  if (url && /^https?:\/\//i.test(url)) uni.setClipboardData({ data: url })
}
const levelLabel = computed(() => {
  const level = assessment.value?.result?.recommendationLevel
  if (level === 'URGENT') return '建议立即寻求专业帮助'
  if (level === 'PROMPT_PROFESSIONAL') return '建议尽快关注'
  if (level === 'ROUTINE_PROFESSIONAL') return '建议进一步咨询'
  return '可先观察变化'
})

onLoad(async (options) => {
  try {
    assessment.value = await api.getAssessment(queryValue(options?.id))
  } catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '加载结果失败', icon: 'none' })
  }
})
</script>

<template>
  <view class="page">
    <StageProgress :current="4" />
    <view class="hero hero--compact">
      <text class="eyebrow">整理完成</text>
      <text class="title title--small">这次情况已经更清楚了</text>
      <text class="subtitle">下面是根据你的结构化输入与追问回答整理的信息摘要。</text>
    </view>

    <view v-if="assessment?.result" class="result-card">
      <view class="score-ring">
        <text class="score-ring__value">✓</text>
      </view>
      <view class="result-card__body">
        <text class="result-card__eyebrow">辅助整理结果</text>
        <text class="result-card__level">{{ levelLabel }}</text>
        <text class="result-card__hint">建议等级用于帮助决定下一步，不代表疾病诊断。</text>
      </view>
    </view>

    <view v-if="assessment" class="card">
      <text class="card-title">情况摘要</text>
      <text class="summary">{{ assessment.result?.summary || '结果仍在整理中。' }}</text>
      <view class="fact-grid">
        <view class="fact"><text class="fact__label">手别</text><text>{{ assessment.input.handSide === 'LEFT' ? '左手' : '右手' }}</text></view>
        <view class="fact"><text class="fact__label">主要症状</text><text>{{ formatSymptoms(assessment.input.symptomTypes) }}</text></view>
        <view class="fact"><text class="fact__label">开始时间</text><text>{{ formatOnset(assessment.input) }}</text></view>
        <view class="fact"><text class="fact__label">粗略位置</text><text>{{ assessment.symptomLocations[0]?.displayLabel || '未填写' }}</text></view>
        <view class="fact"><text class="fact__label">视觉标记</text><text>{{ assessment.visualSources.filter(item => item.annotations.length).length }} 份</text></view>
      </view>
    </view>

    <view v-if="assessment?.result" :class="['advice', { 'advice--attention': assessment.result.recommendationLevel !== 'SELF_MONITOR' }]">
      <text class="advice__title">{{ levelLabel }}</text>
      <text class="advice__text">{{ assessment.result.suggestedNextActions.join('；') }}</text>
    </view>

    <view v-if="assessment?.result?.references?.length" class="card">
      <text class="card-title">本轮检索资料</text>
      <text class="reference-hint">这些是本轮检索到的参考资料，不代表对你个人情况的诊断。</text>
      <view v-for="(source, index) in assessment.result.references" :key="source.chunkId || source.url || index" class="reference">
        <text class="reference-title">{{ source.title }}</text>
        <text class="reference-hint">{{ source.sourceType === 'KNOWLEDGE' ? '知识资料' : '公开网页' }}{{ source.contentVersion ? ` · ${source.contentVersion}` : '' }}</text>
        <text v-if="source.excerpt" class="reference-excerpt">{{ source.excerpt }}</text>
        <text v-if="source.url && /^https?:\/\//i.test(source.url)" class="reference-link" @tap="copySource(source.url)">复制来源链接</text>
      </view>
    </view>

    <view class="notice">{{ assessment?.result?.safetyNotice || '本结果用于帮助你向医生、康复师或教练更准确地描述情况，不构成诊断或治疗建议。' }}</view>
    <view class="sticky-actions">
      <button class="secondary-button" @tap="go('/pages/history/index')">查看历史</button>
      <button class="primary-button" @tap="go('/pages/assessment/start')">记录新情况</button>
    </view>
  </view>
</template>

<style scoped lang="scss">
.hero--compact { padding-top: 8rpx; }
.title--small { font-size: 41rpx; }
.result-card { display: flex; align-items: center; gap: 28rpx; margin-bottom: 24rpx; padding: 31rpx; border-radius: 28rpx; background: linear-gradient(135deg, #143e36, #147461); color: #fff; box-shadow: 0 20rpx 45rpx rgba(17,90,77,.2); }
.score-ring { display: flex; width: 130rpx; height: 130rpx; flex: 0 0 auto; align-items: baseline; justify-content: center; padding-top: 36rpx; border: 10rpx solid rgba(255,255,255,.28); border-top-color: #fff; border-radius: 50%; }
.score-ring__value { font-size: 41rpx; font-weight: 760; }
.score-ring__unit { font-size: 20rpx; }
.result-card__body { flex: 1; }
.result-card__eyebrow { display: block; color: #bde1d8; font-size: 21rpx; }
.result-card__level { display: block; margin-top: 6rpx; font-size: 32rpx; font-weight: 750; }
.result-card__hint { display: block; margin-top: 8rpx; color: rgba(255,255,255,.7); font-size: 20rpx; line-height: 1.5; }
.summary { display: block; margin-top: 19rpx; color: #34423f; font-size: 27rpx; line-height: 1.75; }
.fact-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14rpx; margin-top: 25rpx; }
.fact { padding: 18rpx; border-radius: 17rpx; background: #f5f8f7; color: #31403d; font-size: 24rpx; }
.fact__label { display: block; margin-bottom: 7rpx; color: var(--muted); font-size: 20rpx; }
.advice { margin-bottom: 24rpx; padding: 27rpx; border-left: 8rpx solid #2d8a70; border-radius: 20rpx; background: #e5f3ee; color: #245c4e; }
.advice--attention { border-left-color: #d27e21; background: #fff3df; color: #754d17; }
.advice__title { display: block; margin-bottom: 9rpx; font-size: 28rpx; font-weight: 750; }
.advice__text { display: block; font-size: 24rpx; line-height: 1.65; }
.reference { margin-top: 20rpx; padding-top: 16rpx; border-top: 1rpx solid #e3eae7; }
.reference-title { display: block; font-size: 25rpx; font-weight: 600; }
.reference-hint { display: block; margin-top: 6rpx; color: #60736b; font-size: 21rpx; line-height: 1.6; }
.reference-excerpt { display: block; margin-top: 10rpx; font-size: 23rpx; line-height: 1.7; }
.reference-link { display: block; margin-top: 12rpx; color: #147461; font-size: 23rpx; }
</style>
