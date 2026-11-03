<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import StatusPill from '@/components/StatusPill.vue'
import { api } from '@/api'
import type { Assessment } from '@/types/domain'
import { go } from '@/utils/navigation'

const loading = ref(true)
const assessments = ref<Assessment[]>([])
const recent = computed(() => assessments.value.slice(0, 3))

function formatDate(value: string): string {
  const date = new Date(value)
  return `${date.getMonth() + 1}月${date.getDate()}日 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

async function load(): Promise<void> {
  loading.value = true
  try {
    await api.bootstrap()
    assessments.value = await api.listAssessments()
  } catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '加载记录失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function resume(item: Assessment): void {
  const route = {
    BASIC_INPUT: '/pages/assessment/start',
    LOCATION_3D: '/pages/assessment/location',
    VISUAL_ANNOTATION: '/pages/assessment/location',
    AGENT: '/pages/agent/index',
    RESULT: '/pages/result/index',
  }[item.currentStepKey]
  go(route, { id: item.id })
}

onShow(load)
</script>

<template>
  <view class="page">
    <view class="hero">
      <text class="eyebrow">CLIMB · HAND · CLARITY</text>
      <text class="title">把手部不适，描述得更清楚</text>
      <text class="subtitle">用位置、动作和感受整理一次情况。这里帮助表达，不替代医疗诊断。</text>
    </view>

    <view class="start-card">
      <view class="start-card__badge">约 3–5 分钟</view>
      <text class="start-card__title">开始一次症状记录</text>
      <text class="start-card__text">先填写少量必要信息，再通过可视化位置与有限追问补充细节。</text>
      <button class="primary-button" @tap="go('/pages/assessment/start')">开始记录</button>
    </view>

    <view class="membership-card" @tap="go('/pages/payment/index')">
      <view><text class="membership-card__title">套餐与每日额度</text><text class="membership-card__hint">查看用量、恢复订单与模拟购买 · 不扣款</text></view>
      <text class="membership-card__arrow">→</text>
    </view>

    <view class="section-heading">
      <view>
        <text class="section-heading__title">最近记录</text>
        <text class="section-heading__hint">草稿会自动保存在当前设备</text>
      </view>
      <text class="section-heading__link" @tap="go('/pages/history/index')">查看全部</text>
    </view>

    <view v-if="loading" class="skeleton" />
    <view v-else-if="recent.length === 0" class="card empty">还没有记录。第一次填写时，只需要选择手别、时间和最明显的诱发动作。</view>
    <view v-else>
      <view v-for="item in recent" :key="item.id" class="history-card" @tap="resume(item)">
        <view class="history-card__top">
          <text class="history-card__title">{{ item.input.handSide === 'LEFT' ? '左手' : '右手' }}症状记录</text>
          <StatusPill :status="item.status" />
        </view>
        <text class="history-card__summary">{{ item.symptomLocations[0]?.displayLabel || item.input.trigger || '尚未补充不适位置' }}</text>
        <view class="history-card__bottom">
          <text>{{ formatDate(item.updatedAt) }}</text>
          <text class="history-card__action">{{ item.status === 'COMPLETED' ? '查看结果' : '继续填写' }} →</text>
        </view>
      </view>
    </view>

    <view class="disclaimer">
      <text class="disclaimer__mark">i</text>
      <text>本工具用于整理个人描述，不提供疾病诊断。严重外伤、明显畸形或症状快速加重时，请及时寻求专业帮助。</text>
    </view>
  </view>
</template>

<style scoped lang="scss">
.membership-card { display: flex; align-items: center; justify-content: space-between; margin: -16rpx 0 36rpx; padding: 26rpx 28rpx; border: 1rpx solid #c6ddd5; border-radius: 24rpx; background: #e7f3ed; }
.membership-card__title { display: block; color: #1d5548; font-size: 29rpx; font-weight: 700; }
.membership-card__hint { display: block; margin-top: 9rpx; color: #5f776e; font-size: 21rpx; }
.membership-card__arrow { color: var(--primary); font-size: 34rpx; }
.start-card {
  position: relative;
  overflow: hidden;
  margin-bottom: 42rpx;
  padding: 38rpx;
  border-radius: 32rpx;
  background: linear-gradient(135deg, #163e37, #116b5b);
  box-shadow: 0 24rpx 55rpx rgba(17, 78, 67, 0.22);
  color: #fff;
}
.start-card::after {
  position: absolute;
  top: -70rpx;
  right: -40rpx;
  width: 240rpx;
  height: 240rpx;
  border: 36rpx solid rgba(255,255,255,.07);
  border-radius: 50%;
  content: '';
}
.start-card__badge { display: inline-flex; padding: 9rpx 17rpx; border-radius: 999rpx; background: rgba(255,255,255,.13); font-size: 21rpx; }
.start-card__title { display: block; margin-top: 24rpx; font-size: 36rpx; font-weight: 750; }
.start-card__text { display: block; margin: 14rpx 0 30rpx; color: rgba(255,255,255,.76); font-size: 25rpx; line-height: 1.62; }
.start-card .primary-button { position: relative; z-index: 2; margin: 0; background: #fff; color: #116b5b; box-shadow: none; }
.section-heading { display: flex; align-items: flex-end; justify-content: space-between; margin: 0 6rpx 20rpx; }
.section-heading__title { display: block; font-size: 32rpx; font-weight: 720; }
.section-heading__hint { display: block; margin-top: 6rpx; color: var(--muted); font-size: 21rpx; }
.section-heading__link { color: var(--primary); font-size: 24rpx; font-weight: 650; }
.history-card { margin-bottom: 18rpx; padding: 27rpx 28rpx; border: 1rpx solid var(--line); border-radius: 24rpx; background: #fff; box-shadow: var(--shadow); }
.history-card__top, .history-card__bottom { display: flex; align-items: center; justify-content: space-between; }
.history-card__title { font-size: 29rpx; font-weight: 700; }
.history-card__summary { display: block; overflow: hidden; margin: 16rpx 0 20rpx; color: #53615e; font-size: 25rpx; text-overflow: ellipsis; white-space: nowrap; }
.history-card__bottom { color: #899390; font-size: 22rpx; }
.history-card__action { color: var(--primary); font-weight: 650; }
.disclaimer { display: flex; gap: 16rpx; margin-top: 34rpx; padding: 24rpx; border-radius: 20rpx; background: #e9efed; color: #60706c; font-size: 22rpx; line-height: 1.6; }
.disclaimer__mark { display: flex; width: 34rpx; height: 34rpx; flex: 0 0 auto; align-items: center; justify-content: center; border: 2rpx solid #75837f; border-radius: 50%; font-weight: 700; line-height: 1; }
</style>
