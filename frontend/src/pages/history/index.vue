<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import StatusPill from '@/components/StatusPill.vue'
import { api } from '@/api'
import type { Assessment, AssessmentStatus } from '@/types/domain'
import { go } from '@/utils/navigation'

type Filter = 'ALL' | AssessmentStatus
const loading = ref(true)
const assessments = ref<Assessment[]>([])
const filter = ref<Filter>('ALL')
const filtered = computed(() => filter.value === 'ALL' ? assessments.value : assessments.value.filter((item) => item.status === filter.value))
const filters: Array<{ value: Filter; label: string }> = [
  { value: 'ALL', label: '全部' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'IN_PROGRESS', label: '进行中' },
  { value: 'COMPLETED', label: '已完成' },
]

function formatDate(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

onShow(async () => {
  loading.value = true
  try {
    assessments.value = await api.listAssessments()
  } catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '加载历史失败', icon: 'none' })
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <view class="page">
    <view class="hero hero--compact">
      <text class="eyebrow">HISTORY</text>
      <text class="title title--small">症状记录</text>
      <text class="subtitle">草稿可继续填写，已完成记录保留当时的输入和结果。</text>
    </view>

    <scroll-view class="filter-scroll" scroll-x :show-scrollbar="false">
      <view class="filter-row">
        <text v-for="item in filters" :key="item.value" :class="['filter-chip', { active: filter === item.value }]" @tap="filter = item.value">{{ item.label }}</text>
      </view>
    </scroll-view>

    <view v-if="loading" class="skeleton" />
    <view v-else-if="filtered.length === 0" class="card empty">当前筛选下没有记录。</view>
    <view v-else class="timeline">
      <view v-for="item in filtered" :key="item.id" class="timeline-item" @tap="go('/pages/history/detail', { id: item.id })">
        <view class="timeline-item__dot" />
        <view class="timeline-card">
          <view class="timeline-card__head">
            <text class="timeline-card__date">{{ formatDate(item.updatedAt) }}</text>
            <StatusPill :status="item.status" />
          </view>
          <text class="timeline-card__title">{{ item.input.handSide === 'LEFT' ? '左手' : '右手' }} · {{ item.symptomLocations[0]?.displayLabel || '位置待补充' }}</text>
          <text class="timeline-card__text">{{ item.input.trigger || '动作或情况待补充' }}</text>
          <view class="timeline-card__meta">
            <text>{{ item.visualSources.length }} 份视觉资料</text>
            <text>{{ item.status === 'COMPLETED' ? '查看结果 →' : '查看并恢复 →' }}</text>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.hero--compact { padding-top: 8rpx; }
.title--small { font-size: 41rpx; }
.filter-scroll { width: calc(100% + 56rpx); margin: 0 -28rpx 27rpx; white-space: nowrap; }
.filter-row { display: inline-flex; gap: 12rpx; padding: 0 28rpx; }
.filter-chip { padding: 14rpx 25rpx; border: 1rpx solid var(--line); border-radius: 999rpx; background: #fff; color: var(--muted); font-size: 23rpx; }
.filter-chip.active { border-color: var(--primary); background: var(--primary); color: #fff; }
.timeline { padding-left: 18rpx; }
.timeline-item { position: relative; padding: 0 0 23rpx 31rpx; border-left: 2rpx solid #cfdad7; }
.timeline-item:last-child { border-left-color: transparent; }
.timeline-item__dot { position: absolute; top: 27rpx; left: -9rpx; width: 17rpx; height: 17rpx; border: 4rpx solid #f4f6f8; border-radius: 50%; background: var(--primary); box-shadow: 0 0 0 2rpx var(--primary); }
.timeline-card { padding: 26rpx; border: 1rpx solid var(--line); border-radius: 23rpx; background: #fff; box-shadow: var(--shadow); }
.timeline-card__head, .timeline-card__meta { display: flex; align-items: center; justify-content: space-between; }
.timeline-card__date { color: #81908c; font-size: 21rpx; }
.timeline-card__title { display: block; margin-top: 18rpx; font-size: 29rpx; font-weight: 720; }
.timeline-card__text { display: block; overflow: hidden; margin-top: 10rpx; color: #5e6b68; font-size: 24rpx; text-overflow: ellipsis; white-space: nowrap; }
.timeline-card__meta { margin-top: 22rpx; padding-top: 18rpx; border-top: 1rpx solid #edf0ef; color: var(--primary); font-size: 21rpx; }
</style>
