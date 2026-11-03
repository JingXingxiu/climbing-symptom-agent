<script setup lang="ts">
import { computed } from 'vue'
import type { AssessmentStatus } from '@/types/domain'

const props = defineProps<{ status: AssessmentStatus }>()
const label = computed(() => ({ DRAFT: '草稿', IN_PROGRESS: '进行中', COMPLETED: '已完成' })[props.status])
</script>

<template>
  <text :class="['pill', `pill--${status.toLowerCase()}`]">{{ label }}</text>
</template>

<style scoped lang="scss">
.pill {
  display: inline-flex;
  padding: 8rpx 16rpx;
  border-radius: 999rpx;
  font-size: 21rpx;
  font-weight: 650;
}
.pill--draft { background: #edf0ef; color: #64716e; }
.pill--in_progress { background: #fff0d4; color: #986816; }
.pill--completed { background: #ddf2ec; color: #116b5b; }
</style>
