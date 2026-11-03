<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import PictureAnnotator from '@/components/annotation/PictureAnnotator.vue'
import { api } from '@/api'
import type { PictureAnnotation, VisualSource } from '@/types/domain'
import { queryValue } from '@/utils/navigation'

const assessmentId = ref('')
const source = ref<VisualSource>()
const annotations = ref<PictureAnnotation[]>([])
const description = ref('')
const saving = ref(false)

onLoad(async (options) => {
  assessmentId.value = queryValue(options?.assessmentId)
  const sourceId = queryValue(options?.sourceId)
  try {
    const assessment = await api.getAssessment(assessmentId.value)
    source.value = assessment.visualSources.find((item) => item.id === sourceId)
    if (!source.value) throw new Error('没有找到需要标注的图片')
    annotations.value = [...source.value.annotations]
    description.value = source.value.description ?? ''
  } catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '加载图片失败', icon: 'none' })
  }
})

async function save(): Promise<void> {
  if (!source.value || saving.value) return
  if (!annotations.value.length) {
    uni.showToast({ title: '请至少添加一个位置标记', icon: 'none' })
    return
  }
  saving.value = true
  try {
    source.value = await api.saveAnnotations(source.value.id, annotations.value, description.value.trim())
    uni.showToast({ title: '标记已保存', icon: 'success' })
    setTimeout(() => uni.navigateBack(), 350)
  } catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '保存失败', icon: 'none' })
  } finally {
    saving.value = false
  }
}

function cancel(): void {
  uni.navigateBack()
}
</script>

<template>
  <view class="editor-page">
    <view class="editor-header">
      <view>
        <text class="editor-title">在图片上标出不适区域</text>
        <text class="editor-subtitle">点选适合单点，画圈适合局部区域，自由画适合不规则范围。</text>
      </view>
      <text class="source-tag">
        {{ source?.type === 'THREE_D_SNAPSHOT' ? '3D 视角' : source?.type === 'STANDARD_IMAGE' ? '标准图' : '我的图片' }}
      </text>
    </view>

    <view v-if="!source" class="skeleton editor-loading" />
    <PictureAnnotator v-else v-model="annotations" :image-src="source.pictureUrl" />

    <view class="description-box">
      <text class="label">补充说明（选填）</text>
      <textarea v-model="description" class="textarea" maxlength="160" placeholder="例如：红圈靠近关节侧面，按压最明显" />
    </view>

    <view class="editor-actions">
      <button class="secondary-button" @tap="cancel">取消</button>
      <button class="primary-button" :loading="saving" @tap="save">保存标记</button>
    </view>
  </view>
</template>

<style scoped lang="scss">
.editor-page { min-height: 100vh; padding: 24rpx 24rpx max(32rpx, env(safe-area-inset-bottom)); background: #eef2f1; }
.editor-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20rpx; padding: 8rpx 4rpx 22rpx; }
.editor-title { display: block; font-size: 32rpx; font-weight: 750; }
.editor-subtitle { display: block; max-width: 530rpx; margin-top: 8rpx; color: var(--muted); font-size: 22rpx; line-height: 1.5; }
.source-tag { flex: 0 0 auto; padding: 8rpx 13rpx; border-radius: 999rpx; background: var(--primary-soft); color: var(--primary); font-size: 19rpx; }
.editor-loading { height: 760rpx; }
.description-box { margin-top: 20rpx; padding: 24rpx; border-radius: 22rpx; background: #fff; }
.description-box .textarea { min-height: 120rpx; }
.editor-actions { display: flex; gap: 14rpx; margin-top: 22rpx; }
.editor-actions button { flex: 1; }
</style>
