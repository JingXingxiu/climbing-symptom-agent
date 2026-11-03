<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import StatusPill from '@/components/StatusPill.vue'
import { api } from '@/api'
import type { Assessment } from '@/types/domain'
import { go, queryValue } from '@/utils/navigation'
import { formatSymptoms } from '@/utils/symptoms'
import { formatOnset } from '@/utils/onset'

const assessment = ref<Assessment>()

onLoad(async (options) => {
  try { assessment.value = await api.getAssessment(queryValue(options?.id)) }
  catch (error) { uni.showToast({ title: error instanceof Error ? error.message : '加载失败', icon: 'none' }) }
})

function resume(): void {
  if (!assessment.value) return
  const route = {
    BASIC_INPUT: '/pages/assessment/start',
    LOCATION_3D: '/pages/assessment/location',
    VISUAL_ANNOTATION: '/pages/assessment/location',
    AGENT: '/pages/agent/index',
    RESULT: '/pages/result/index',
  }[assessment.value.currentStepKey]
  go(route, { id: assessment.value.id })
}
</script>

<template>
  <view v-if="assessment" class="page">
    <view class="detail-head">
      <view>
        <text class="eyebrow">ASSESSMENT</text>
        <text class="title title--small">{{ assessment.input.handSide === 'LEFT' ? '左手' : '右手' }}症状记录</text>
      </view>
      <StatusPill :status="assessment.status" />
    </view>

    <view class="card">
      <text class="card-title">首次输入</text>
      <view class="detail-list">
        <view><text>主要症状</text><text>{{ formatSymptoms(assessment.input.symptomTypes) }}</text></view>
        <view><text>开始时间</text><text>{{ formatOnset(assessment.input) }}</text></view>
        <view><text>最明显情况</text><text>{{ assessment.input.trigger || '未填写' }}</text></view>
        <view><text>正常使用</text><text>{{ { NONE: '不影响', MILD: '有点影响', SIGNIFICANT: '明显影响' }[assessment.input.normalUseImpact] }}</text></view>
        <view><text>补充说明</text><text>{{ assessment.input.additionalDescription || '无' }}</text></view>
      </view>
    </view>

    <view class="card">
      <text class="card-title">视觉位置</text>
      <text class="location-line">{{ assessment.symptomLocations[0]?.displayLabel || '粗位置未填写' }}</text>
      <view v-if="assessment.visualSources.length" class="visual-grid">
        <view v-for="source in assessment.visualSources" :key="source.id" class="visual-item">
          <image :src="source.previewUrl || source.pictureUrl" mode="aspectFill" />
          <text>{{ source.annotations.length }} 个标记</text>
        </view>
      </view>
      <text v-else class="muted">没有视觉资料</text>
    </view>

    <view v-if="assessment.result" class="card">
      <text class="card-title">整理结果</text>
      <text class="result-summary">{{ assessment.result.summary }}</text>
      <view class="result-advice">{{ assessment.result.suggestedNextActions.join('；') }}</view>
    </view>

    <button class="primary-button" @tap="resume">{{ assessment.status === 'COMPLETED' ? '查看完整结果' : '从当前步骤继续' }}</button>
  </view>
  <view v-else class="page"><view class="skeleton" /></view>
</template>

<style scoped lang="scss">
.detail-head { display: flex; align-items: center; justify-content: space-between; padding: 20rpx 7rpx 32rpx; }
.title--small { font-size: 41rpx; }
.detail-list { margin-top: 20rpx; }
.detail-list view { display: grid; grid-template-columns: 150rpx 1fr; gap: 20rpx; padding: 19rpx 0; border-bottom: 1rpx solid #edf0ef; color: #33413e; font-size: 24rpx; line-height: 1.5; }
.detail-list view text:first-child { color: var(--muted); }
.detail-list view:last-child { border: 0; }
.location-line { display: block; margin: 16rpx 0; color: var(--primary); font-size: 27rpx; font-weight: 700; }
.visual-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12rpx; }
.visual-item { position: relative; overflow: hidden; height: 165rpx; border-radius: 15rpx; background: #eef1f0; }
.visual-item image { width: 100%; height: 100%; }
.visual-item text { position: absolute; right: 7rpx; bottom: 7rpx; padding: 5rpx 9rpx; border-radius: 999rpx; background: rgba(20,35,32,.72); color: #fff; font-size: 18rpx; }
.muted { color: var(--muted); font-size: 23rpx; }
.result-summary { display: block; margin-top: 17rpx; color: #34423f; font-size: 25rpx; line-height: 1.7; }
.result-advice { margin-top: 19rpx; padding: 19rpx; border-radius: 16rpx; background: #edf6f3; color: #2e6858; font-size: 23rpx; line-height: 1.6; }
</style>
