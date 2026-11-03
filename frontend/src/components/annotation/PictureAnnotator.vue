<script setup lang="ts">
import { getCurrentInstance, nextTick, onMounted, ref, watch } from 'vue'
import type { AnnotationKind, NormalizedPoint, PictureAnnotation } from '@/types/domain'
import { createId } from '@/utils/id'

const props = withDefaults(defineProps<{
  imageSrc: string
  modelValue: PictureAnnotation[]
  readonly?: boolean
}>(), {
  readonly: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: PictureAnnotation[]]
  change: [value: PictureAnnotation[]]
}>()

interface Rect { x: number; y: number; width: number; height: number }
interface Draft { kind: AnnotationKind; points: NormalizedPoint[]; radius?: number }

const instance = getCurrentInstance()
const canvasId = `annotation-${Math.random().toString(36).slice(2, 9)}`
const stage = ref<Rect>({ x: 0, y: 0, width: 1, height: 1 })
const stageOffset = ref({ left: 0, top: 0 })
const imageRect = ref<Rect>({ x: 0, y: 0, width: 1, height: 1 })
const activeTool = ref<AnnotationKind>('CIRCLE')
const draft = ref<Draft>()
const ready = ref(false)
const drawing = ref(false)

const tools: Array<{ value: AnnotationKind; label: string; icon: string }> = [
  { value: 'POINT', label: '点选', icon: '•' },
  { value: 'CIRCLE', label: '画圈', icon: '○' },
  { value: 'FREEHAND', label: '自由画', icon: '✎' },
]

function getTouch(event: TouchEvent): { x: number; y: number } | undefined {
  const touch = event.touches[0] ?? event.changedTouches[0]
  if (!touch) return undefined
  const miniProgramTouch = touch as Touch & { x?: number; y?: number }
  if (miniProgramTouch.x !== undefined && miniProgramTouch.y !== undefined) {
    return { x: miniProgramTouch.x, y: miniProgramTouch.y }
  }
  return {
    x: touch.clientX - stageOffset.value.left,
    y: touch.clientY - stageOffset.value.top,
  }
}

function normalizedFromTouch(event: TouchEvent): NormalizedPoint | undefined {
  const touch = getTouch(event)
  if (!touch) return undefined
  const rect = imageRect.value
  const localX = touch.x - rect.x
  const localY = touch.y - rect.y
  if (localX < 0 || localY < 0 || localX > rect.width || localY > rect.height) return undefined
  return {
    x: Math.min(1, Math.max(0, localX / rect.width)),
    y: Math.min(1, Math.max(0, localY / rect.height)),
  }
}

function absolute(point: NormalizedPoint): NormalizedPoint {
  const rect = imageRect.value
  return { x: rect.x + point.x * rect.width, y: rect.y + point.y * rect.height }
}

function drawAnnotation(ctx: UniApp.CanvasContext, annotation: Pick<PictureAnnotation, 'kind' | 'points' | 'radius'>): void {
  if (!annotation.points.length) return
  const first = absolute(annotation.points[0])
  ctx.beginPath()
  ctx.setLineCap('round')
  ctx.setLineJoin('round')
  ctx.setStrokeStyle('#dc4d43')
  ctx.setFillStyle('rgba(220, 77, 67, 0.24)')
  ctx.setLineWidth(4)

  if (annotation.kind === 'POINT') {
    ctx.arc(first.x, first.y, 9, 0, Math.PI * 2)
    ctx.fill()
    ctx.beginPath()
    ctx.arc(first.x, first.y, 15, 0, Math.PI * 2)
    ctx.stroke()
    return
  }
  if (annotation.kind === 'CIRCLE') {
    const radius = (annotation.radius ?? 0.06) * Math.min(imageRect.value.width, imageRect.value.height)
    ctx.arc(first.x, first.y, radius, 0, Math.PI * 2)
    ctx.fill()
    ctx.stroke()
    return
  }
  ctx.moveTo(first.x, first.y)
  annotation.points.slice(1).forEach((point) => {
    const target = absolute(point)
    ctx.lineTo(target.x, target.y)
  })
  ctx.stroke()
}

function redraw(): void {
  if (!ready.value) return
  const ctx = uni.createCanvasContext(canvasId, instance?.proxy)
  ctx.clearRect(0, 0, stage.value.width, stage.value.height)
  const rect = imageRect.value
  if (props.imageSrc) ctx.drawImage(props.imageSrc, rect.x, rect.y, rect.width, rect.height)
  props.modelValue.forEach((annotation) => drawAnnotation(ctx, annotation))
  if (draft.value) drawAnnotation(ctx, draft.value)
  ctx.draw()
}

function measure(): Promise<void> {
  return new Promise((resolve) => {
    uni.createSelectorQuery()
      .in(instance?.proxy)
      .select('.annotation-stage')
      .boundingClientRect((result) => {
        const rect = result as UniApp.NodeInfo
        const width = Number(rect.width ?? 1)
        const height = Number(rect.height ?? 1)
        stageOffset.value = { left: Number(rect.left ?? 0), top: Number(rect.top ?? 0) }
        stage.value = { x: 0, y: 0, width, height }
        uni.getImageInfo({
          src: props.imageSrc,
          success: (info) => {
            const scale = Math.min(width / info.width, height / info.height)
            const renderedWidth = info.width * scale
            const renderedHeight = info.height * scale
            imageRect.value = {
              x: (width - renderedWidth) / 2,
              y: (height - renderedHeight) / 2,
              width: renderedWidth,
              height: renderedHeight,
            }
            ready.value = true
            redraw()
            resolve()
          },
          fail: () => {
            imageRect.value = { x: 0, y: 0, width, height }
            ready.value = true
            redraw()
            resolve()
          },
        })
      })
      .exec()
  })
}

function start(event: TouchEvent): void {
  if (props.readonly) return
  const point = normalizedFromTouch(event)
  if (!point) return
  drawing.value = true
  draft.value = { kind: activeTool.value, points: [point], radius: activeTool.value === 'CIRCLE' ? 0.02 : undefined }
  redraw()
}

function move(event: TouchEvent): void {
  if (!drawing.value || !draft.value || props.readonly) return
  const point = normalizedFromTouch(event)
  if (!point) return
  if (draft.value.kind === 'CIRCLE') {
    const origin = draft.value.points[0]
    const rect = imageRect.value
    const radiusPx = Math.hypot(
      (point.x - origin.x) * rect.width,
      (point.y - origin.y) * rect.height,
    )
    draft.value.radius = Math.min(0.5, Math.max(0.02, radiusPx / Math.min(rect.width, rect.height)))
  } else if (draft.value.kind === 'FREEHAND') {
    const last = draft.value.points.at(-1)
    if (!last || Math.hypot(point.x - last.x, point.y - last.y) > 0.008) draft.value.points.push(point)
  }
  redraw()
}

function end(): void {
  if (!drawing.value || !draft.value || props.readonly) return
  drawing.value = false
  const annotation: PictureAnnotation = {
    id: createId('annotation'),
    kind: draft.value.kind,
    points: [...draft.value.points],
    radius: draft.value.radius,
    color: '#dc4d43',
    strokeWidth: 4,
    createdAt: new Date().toISOString(),
  }
  const updated = [...props.modelValue, annotation]
  draft.value = undefined
  emit('update:modelValue', updated)
  emit('change', updated)
}

function undo(): void {
  const updated = props.modelValue.slice(0, -1)
  emit('update:modelValue', updated)
  emit('change', updated)
}

function clear(): void {
  emit('update:modelValue', [])
  emit('change', [])
}

function exportSnapshot(): Promise<string> {
  return new Promise((resolve, reject) => {
    uni.canvasToTempFilePath({
      canvasId,
      fileType: 'png',
      quality: 0.92,
      success: ({ tempFilePath }) => resolve(tempFilePath),
      fail: (error) => reject(new Error(error.errMsg)),
    }, instance?.proxy)
  })
}

watch(() => props.modelValue, () => nextTick(redraw), { deep: true })
watch(() => props.imageSrc, () => nextTick(measure))
onMounted(() => nextTick(measure))

defineExpose({ exportSnapshot, redraw })
</script>

<template>
  <view class="annotator">
    <view class="annotation-stage">
      <canvas
        :canvas-id="canvasId"
        :id="canvasId"
        class="annotation-canvas"
        disable-scroll
        @touchstart="start"
        @touchmove="move"
        @touchend="end"
        @touchcancel="end"
      />
      <view v-if="!ready" class="annotation-loading">正在准备图片…</view>
    </view>

    <view v-if="!readonly" class="toolbar">
      <view class="toolbar__tools">
        <button
          v-for="tool in tools"
          :key="tool.value"
          :class="['tool', { 'tool--active': activeTool === tool.value }]"
          @tap="activeTool = tool.value"
        >
          <text class="tool__icon">{{ tool.icon }}</text>
          <text>{{ tool.label }}</text>
        </button>
      </view>
      <view class="toolbar__actions">
        <button class="small-button" :disabled="modelValue.length === 0" @tap="undo">撤销</button>
        <button class="small-button small-button--danger" :disabled="modelValue.length === 0" @tap="clear">清空</button>
      </view>
    </view>
    <text class="annotation-count">已添加 {{ modelValue.length }} 个标记 · 坐标按图片尺寸归一化保存</text>
  </view>
</template>

<style scoped lang="scss">
.annotator { width: 100%; }
.annotation-stage {
  position: relative;
  width: 100%;
  height: 760rpx;
  overflow: hidden;
  border-radius: 24rpx;
  background:
    linear-gradient(45deg, #eef1f0 25%, transparent 25%),
    linear-gradient(-45deg, #eef1f0 25%, transparent 25%),
    linear-gradient(45deg, transparent 75%, #eef1f0 75%),
    linear-gradient(-45deg, transparent 75%, #eef1f0 75%),
    #f8faf9;
  background-position: 0 0, 0 16rpx, 16rpx -16rpx, -16rpx 0;
  background-size: 32rpx 32rpx;
}
.annotation-canvas { width: 100%; height: 100%; }
.annotation-loading {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(248, 250, 249, 0.88);
  color: var(--muted);
  font-size: 25rpx;
}
.toolbar { margin-top: 20rpx; }
.toolbar__tools { display: flex; gap: 12rpx; }
.tool {
  display: flex;
  height: 78rpx;
  flex: 1;
  align-items: center;
  justify-content: center;
  gap: 8rpx;
  border: 1rpx solid var(--line);
  border-radius: 18rpx;
  background: #fff;
  color: var(--muted);
  font-size: 24rpx;
  line-height: 1;
}
.tool--active { border-color: var(--primary); background: var(--primary-soft); color: var(--primary); }
.tool__icon { font-size: 32rpx; line-height: 1; }
.toolbar__actions { display: flex; justify-content: flex-end; gap: 14rpx; margin-top: 16rpx; }
.small-button {
  min-width: 120rpx;
  height: 62rpx;
  margin: 0;
  border: 1rpx solid var(--line);
  border-radius: 31rpx;
  background: #fff;
  color: #53615e;
  font-size: 23rpx;
  line-height: 62rpx;
}
.small-button--danger { color: var(--danger); }
.small-button[disabled] { color: #aab4b2; }
.annotation-count { display: block; margin-top: 14rpx; color: var(--muted); font-size: 22rpx; text-align: center; }
</style>
