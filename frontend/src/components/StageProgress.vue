<script setup lang="ts">
defineProps<{ current: 1 | 2 | 3 | 4 }>()

const labels = ['基础', '位置', '追问', '结果']
</script>

<template>
  <view class="progress">
    <view v-for="(label, index) in labels" :key="label" class="progress__item">
      <view :class="['progress__dot', { 'progress__dot--active': index + 1 <= current }]">
        {{ index + 1 }}
      </view>
      <text :class="['progress__label', { 'progress__label--active': index + 1 <= current }]">
        {{ label }}
      </text>
      <view v-if="index < labels.length - 1" :class="['progress__line', { 'progress__line--active': index + 1 < current }]" />
    </view>
  </view>
</template>

<style scoped lang="scss">
.progress {
  display: flex;
  align-items: flex-start;
  margin: 4rpx 0 30rpx;
  padding: 0 10rpx;
}

.progress__item {
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
}

.progress__dot {
  position: relative;
  z-index: 2;
  display: flex;
  width: 46rpx;
  height: 46rpx;
  align-items: center;
  justify-content: center;
  border: 2rpx solid #cfd8d5;
  border-radius: 50%;
  background: #f4f6f8;
  color: #84908e;
  font-size: 22rpx;
}

.progress__dot--active {
  border-color: var(--primary);
  background: var(--primary);
  color: #fff;
}

.progress__label {
  margin-top: 9rpx;
  color: #87928f;
  font-size: 21rpx;
}

.progress__label--active {
  color: var(--primary);
  font-weight: 650;
}

.progress__line {
  position: absolute;
  z-index: 1;
  top: 22rpx;
  left: calc(50% + 23rpx);
  width: calc(100% - 46rpx);
  height: 3rpx;
  background: #d8dfdd;
}

.progress__line--active {
  background: var(--primary);
}
</style>
