<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import StageProgress from '@/components/StageProgress.vue'
import Hand3DViewer from '@/components/hand-3d/Hand3DViewer.vue'
import { api } from '@/api'
import type { Assessment, FingerCode, JointCode, SymptomLocation, VisualSource } from '@/types/domain'
import type { Hand3DSnapshotPayload } from '@/types/hand3d'
import { go, queryValue } from '@/utils/navigation'

type Tab = 'THREE_D' | 'STANDARD' | 'UPLOAD'
type InteractionMode = 'NAVIGATE' | 'SELECT' | 'LOCKED'

interface ViewerExposed {
  captureSnapshot?: () => Promise<Hand3DSnapshotPayload | void>
}

const assessmentId = ref('')
const assessment = ref<Assessment>()
const activeTab = ref<Tab>('THREE_D')
const viewer = ref<ViewerExposed>()
const interactionMode = ref<InteractionMode>('SELECT')
type LocationPart = FingerCode | 'PALM' | 'WRIST'

const selectedPart = ref<LocationPart>()
const selectedJoint = ref<JointCode>()
const uploading = ref(false)
const snapshotting = ref(false)

const locationParts: Array<{ value: LocationPart; label: string }> = [
  { value: 'THUMB', label: '拇指' },
  { value: 'INDEX', label: '食指' },
  { value: 'MIDDLE', label: '中指' },
  { value: 'RING', label: '无名指' },
  { value: 'LITTLE', label: '小指' },
  { value: 'PALM', label: '手掌' },
  { value: 'WRIST', label: '手腕' },
]
const joints: Array<{ value: JointCode; label: string }> = [
  { value: 'TIP', label: '指尖' },
  { value: 'DIP', label: '远端关节' },
  { value: 'PIP', label: '近端关节' },
  { value: 'MCP', label: '指根' },
]
const standardViews = [
  { view: 'PALMAR' as const, name: '掌面', url: '/static/hand/hand-palm.png' },
  { view: 'DORSAL' as const, name: '背面', url: '/static/hand/hand-dorsal.png' },
  { view: 'LATERAL' as const, name: '侧面', url: '/static/hand/hand-side.png' },
]

const sourceCount = computed(() => assessment.value?.visualSources.length ?? 0)
const annotatedCount = computed(() => assessment.value?.visualSources.filter((source) => source.annotations.length > 0).length ?? 0)
const uploadedSources = computed(() => assessment.value?.visualSources.filter((source) => source.type === 'USER_IMAGE') ?? [])
const selectedLocation = computed(() => assessment.value?.symptomLocations[0])
const visibleJoints = computed(() => selectedPart.value === 'THUMB'
  ? joints.filter((item) => item.value !== 'DIP')
  : joints)

function partLabel(value: LocationPart): string {
  return locationParts.find((item) => item.value === value)?.label ?? '手部'
}

function jointLabel(value: JointCode): string {
  return joints.find((item) => item.value === value)?.label ?? '关节'
}

function regionJoint(value: JointCode): string {
  if (value === 'TIP') return 'distal'
  return value.toLowerCase()
}

function buildSymptomLocation(): SymptomLocation | undefined {
  if (!assessment.value || !selectedPart.value) return undefined
  const side = assessment.value.input.handSide
  if (selectedPart.value === 'PALM' || selectedPart.value === 'WRIST') {
    const structure = selectedPart.value === 'PALM' ? 'palm.center' : 'wrist.center'
    return {
      handSide: side,
      regionId: `hand.${side.toLowerCase()}.${structure}`,
      displayLabel: `${side === 'LEFT' ? '左手' : '右手'} · ${partLabel(selectedPart.value)}附近`,
    }
  }
  if (!selectedJoint.value) return undefined
  const jointPath = selectedPart.value === 'THUMB' && selectedJoint.value === 'PIP'
    ? 'ip'
    : regionJoint(selectedJoint.value)
  return {
    handSide: side,
    fingerCode: selectedPart.value,
    jointCode: selectedJoint.value,
    regionId: `hand.${side.toLowerCase()}.${selectedPart.value.toLowerCase()}.${jointPath}`,
    displayLabel: `${side === 'LEFT' ? '左手' : '右手'} · ${partLabel(selectedPart.value)} · ${jointLabel(selectedJoint.value)}附近`,
  }
}

async function load(): Promise<void> {
  if (!assessmentId.value) return
  assessment.value = await api.getAssessment(assessmentId.value)
  const location = assessment.value.symptomLocations[0]
  if (location?.fingerCode) selectedPart.value = location.fingerCode
  else if (location?.regionId.includes('.palm.')) selectedPart.value = 'PALM'
  else if (location?.regionId.includes('.wrist.')) selectedPart.value = 'WRIST'
  selectedJoint.value = location?.jointCode
}

onLoad(async (options) => {
  assessmentId.value = queryValue(options?.id)
  try {
    await load()
  } catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '加载失败', icon: 'none' })
  }
})
onShow(() => { if (assessmentId.value) void load() })

function updateManualRegion(): void {
  if (!assessment.value) return
  const location = buildSymptomLocation()
  assessment.value.symptomLocations = location ? [location] : []
}

function selectPart(part: LocationPart): void {
  selectedPart.value = part
  if (part === 'THUMB' && selectedJoint.value === 'DIP') selectedJoint.value = undefined
  if (part === 'PALM' || part === 'WRIST' || selectedJoint.value) updateManualRegion()
}

function handleRegionChange(location: SymptomLocation): void {
  if (!assessment.value) return
  assessment.value.symptomLocations = [location]
  if (location.fingerCode) selectedPart.value = location.fingerCode
  else if (location.regionId.includes('.palm.')) selectedPart.value = 'PALM'
  else if (location.regionId.includes('.wrist.')) selectedPart.value = 'WRIST'
  selectedJoint.value = location.jointCode
}

async function requestSnapshot(): Promise<void> {
  if (!assessment.value) return
  if (!assessment.value.symptomLocations.length) {
    uni.showToast({ title: '请先选择粗略位置', icon: 'none' })
    return
  }
  snapshotting.value = true
  interactionMode.value = 'LOCKED'
  try {
    await viewer.value?.captureSnapshot?.()
  } catch (error) {
    interactionMode.value = 'SELECT'
    uni.showToast({ title: error instanceof Error ? error.message : '生成视角图片失败', icon: 'none' })
  } finally {
    snapshotting.value = false
  }
}

async function handleSnapshot(payload: Hand3DSnapshotPayload): Promise<void> {
  if (!assessment.value) return
  try {
    const source = await api.addVisualSource({
      assessmentId: assessment.value.id,
      type: 'THREE_D_SNAPSHOT',
      pictureUrl: payload.pictureUrl,
      width: payload.width,
      height: payload.height,
      viewState: payload.viewState,
    })
    assessment.value.symptomLocations = payload.symptomLocations
    assessment.value.visualSources.push(source)
    await api.saveAssessment(assessment.value)
    editSource(source)
  } catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '保存 3D 视角失败', icon: 'none' })
  } finally {
    interactionMode.value = 'SELECT'
  }
}

async function editStandard(view: typeof standardViews[number]): Promise<void> {
  if (!assessment.value) return
  try {
    let source = assessment.value.visualSources.find((item) => item.type === 'STANDARD_IMAGE' && item.standardView === view.view)
    if (!source) {
      source = await api.addVisualSource({
        assessmentId: assessment.value.id,
        type: 'STANDARD_IMAGE',
        pictureUrl: view.url,
        standardView: view.view,
        uploadStatus: 'READY',
      })
      assessment.value.visualSources.push(source)
    }
    editSource(source)
  } catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '打开标准图片失败', icon: 'none' })
  }
}

async function choosePictures(): Promise<void> {
  if (!assessment.value || uploading.value) return
  try {
    const result = await new Promise<{ tempFiles: Array<{ tempFilePath: string; size?: number }> }>((resolve, reject) => {
      uni.chooseMedia({
        count: 6,
        mediaType: ['image'],
        sourceType: ['album', 'camera'],
        success: (value) => resolve(value as { tempFiles: Array<{ tempFilePath: string; size?: number }> }),
        fail: reject,
      })
    })
    uploading.value = true
    const accepted = result.tempFiles.filter((file) => !file.size || file.size <= 10 * 1024 * 1024)
    if (accepted.length !== result.tempFiles.length) {
      uni.showToast({ title: '已跳过超过 10 MB 的图片', icon: 'none' })
    }
    for (const file of accepted) {
      const source = await api.uploadUserPicture(assessment.value.id, file.tempFilePath)
      assessment.value.visualSources.push(source)
    }
    if (accepted.length) uni.showToast({ title: `已添加 ${accepted.length} 张`, icon: 'success' })
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    if (!/cancel/i.test(message)) uni.showToast({ title: '选择图片失败', icon: 'none' })
  } finally {
    uploading.value = false
  }
}

function handleViewerError(error: { message: string }): void {
  interactionMode.value = 'SELECT'
  snapshotting.value = false
  uni.showToast({ title: error.message, icon: 'none' })
}

function editSource(source: VisualSource): void {
  go('/pages/annotation/editor', { assessmentId: assessmentId.value, sourceId: source.id })
}

async function continueToAgent(): Promise<void> {
  if (!assessment.value) return
  if (!assessment.value.symptomLocations.length) {
    uni.showToast({ title: '请先选择粗略不适位置', icon: 'none' })
    return
  }
  if (annotatedCount.value === 0) {
    uni.showToast({ title: '请至少在一张图片上标记不适区域', icon: 'none' })
    return
  }
  assessment.value.currentStepKey = 'AGENT'
  assessment.value.status = 'IN_PROGRESS'
  try {
    await api.saveAssessment(assessment.value)
    go('/pages/agent/index', { id: assessment.value.id })
  } catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '保存位置失败', icon: 'none' })
  }
}
</script>

<template>
  <view class="page">
    <StageProgress :current="2" />
    <view class="hero hero--compact">
      <text class="eyebrow">STEP 02</text>
      <text class="title title--small">不适大概在哪里？</text>
      <text class="subtitle">先选一个容易理解的粗位置，再在图片上圈出更精确的位置。</text>
    </view>

    <view v-if="assessment" class="location-summary">
      <text class="location-summary__side">{{ assessment.input.handSide === 'LEFT' ? '左手' : '右手' }}</text>
      <text class="location-summary__location">{{ selectedLocation?.displayLabel || '请选择粗略位置' }}</text>
      <text class="location-summary__count">{{ sourceCount }} 个视觉资料 · {{ annotatedCount }} 个已标注</text>
    </view>

    <view class="source-tabs">
      <view :class="['source-tab', { 'source-tab--active': activeTab === 'THREE_D' }]" @tap="activeTab = 'THREE_D'">3D 手部</view>
      <view :class="['source-tab', { 'source-tab--active': activeTab === 'STANDARD' }]" @tap="activeTab = 'STANDARD'">标准图片</view>
      <view :class="['source-tab', { 'source-tab--active': activeTab === 'UPLOAD' }]" @tap="activeTab = 'UPLOAD'">我的图片</view>
    </view>

    <view v-if="assessment && activeTab === 'THREE_D'" class="card visual-card">
      <view class="viewer-modes">
        <button :class="['mode-button', { active: interactionMode === 'NAVIGATE' }]" @tap="interactionMode = 'NAVIGATE'">旋转 / 缩放</button>
        <button :class="['mode-button', { active: interactionMode === 'SELECT' }]" @tap="interactionMode = 'SELECT'">点击选区域</button>
      </view>
      <Hand3DViewer
        ref="viewer"
        :hand-side="assessment.input.handSide"
        :selected-region-id="selectedLocation?.regionId"
        :interaction-mode="interactionMode"
        @region-change="handleRegionChange"
        @snapshot="handleSnapshot"
        @error="handleViewerError"
      />

      <view class="coarse-panel">
        <text class="label">粗略位置</text>
        <view class="chip-row">
          <text
            v-for="item in locationParts"
            :key="item.value"
            :class="['chip', { 'chip--active': selectedPart === item.value }]"
            @tap="selectPart(item.value)"
          >{{ item.label }}</text>
        </view>
        <view v-if="selectedPart && !['PALM', 'WRIST'].includes(selectedPart)" class="chip-row segment-row">
          <text
            v-for="item in visibleJoints"
            :key="item.value"
            :class="['chip', { 'chip--active': selectedJoint === item.value }]"
            @tap="selectedJoint = item.value; updateManualRegion()"
          >{{ item.label }}</text>
        </view>
      </view>
      <button class="primary-button capture-button" :loading="snapshotting" @tap="requestSnapshot">固定当前视角并精细标记</button>
    </view>

    <view v-if="activeTab === 'STANDARD'" class="card visual-card">
      <text class="card-title">选择标准视图</text>
      <text class="card-subtitle">进入后可点选、画圈或自由绘制。左右手信息单独保存，不依赖图片朝向猜测。</text>
      <view class="standard-grid">
        <view v-for="view in standardViews" :key="view.view" class="standard-item" @tap="editStandard(view)">
          <image class="standard-item__image" :src="view.url" mode="aspectFit" />
          <view class="standard-item__footer">
            <text>{{ view.name }}</text>
            <text class="standard-item__action">标记 →</text>
          </view>
        </view>
      </view>
    </view>

    <view v-if="activeTab === 'UPLOAD'" class="card visual-card">
      <text class="card-title">添加自己的手部照片</text>
      <text class="card-subtitle">支持相册或相机，一次最多选择 6 张。演示模式只保存在本机，真实接口将上传至 MinIO。</text>
      <button class="upload-button" :loading="uploading" @tap="choosePictures">
        <text class="upload-button__plus">＋</text>
        <text>选择图片</text>
      </button>
      <view v-if="uploadedSources.length" class="upload-grid">
        <view v-for="source in uploadedSources" :key="source.id" class="upload-item" @tap="editSource(source)">
          <image class="upload-item__image" :src="source.previewUrl || source.pictureUrl" mode="aspectFill" />
          <text :class="['upload-item__status', { done: source.annotations.length > 0 }]">
            {{ source.annotations.length > 0 ? `已标记 ${source.annotations.length}` : '待标记' }}
          </text>
        </view>
      </view>
    </view>

    <view class="notice">视觉标记只用于帮助表达症状位置，不会自动判断病因或提供诊断。</view>
    <view class="sticky-actions">
      <button class="secondary-button" @tap="go('/pages/assessment/start', { id: assessmentId })">返回修改</button>
      <button class="primary-button" @tap="continueToAgent">继续辅助追问</button>
    </view>
  </view>
</template>

<style scoped lang="scss">
.hero--compact { padding-top: 8rpx; }
.title--small { font-size: 41rpx; }
.location-summary { display: flex; align-items: center; gap: 12rpx; margin-bottom: 22rpx; padding: 19rpx 22rpx; border-radius: 19rpx; background: #e4f1ed; color: var(--primary); }
.location-summary__side { font-size: 25rpx; font-weight: 750; }
.location-summary__location { flex: 1; font-size: 24rpx; }
.location-summary__count { color: #65827b; font-size: 20rpx; }
.source-tabs { display: flex; margin-bottom: 20rpx; padding: 7rpx; border-radius: 20rpx; background: #e8eceb; }
.source-tab { flex: 1; padding: 18rpx 8rpx; border-radius: 15rpx; color: #687572; font-size: 24rpx; text-align: center; }
.source-tab--active { background: #fff; color: var(--primary); font-weight: 700; box-shadow: 0 6rpx 18rpx rgba(34,55,50,.08); }
.visual-card { padding: 22rpx; }
.viewer-modes { display: flex; gap: 12rpx; margin-bottom: 16rpx; }
.mode-button { flex: 1; height: 68rpx; margin: 0; border: 1rpx solid var(--line); border-radius: 16rpx; background: #fff; color: var(--muted); font-size: 23rpx; line-height: 68rpx; }
.mode-button.active { border-color: var(--primary); background: var(--primary-soft); color: var(--primary); }
.coarse-panel { margin-top: 22rpx; padding: 24rpx; border-radius: 20rpx; background: #f7f9f8; }
.segment-row { margin-top: 14rpx; padding-top: 16rpx; border-top: 1rpx solid var(--line); }
.capture-button { margin-top: 22rpx; }
.standard-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14rpx; margin-top: 24rpx; }
.standard-item { overflow: hidden; border: 1rpx solid var(--line); border-radius: 18rpx; background: #f6f8f7; }
.standard-item__image { width: 100%; height: 260rpx; }
.standard-item__footer { display: flex; align-items: center; justify-content: space-between; padding: 15rpx 14rpx; background: #fff; font-size: 23rpx; }
.standard-item__action { color: var(--primary); font-size: 20rpx; }
.upload-button { display: flex; height: 180rpx; flex-direction: column; align-items: center; justify-content: center; margin-top: 24rpx; border: 2rpx dashed #a8bcb7; border-radius: 22rpx; background: #f5faf8; color: var(--primary); font-size: 25rpx; line-height: 1.4; }
.upload-button__plus { font-size: 50rpx; font-weight: 300; }
.upload-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14rpx; margin-top: 20rpx; }
.upload-item { position: relative; overflow: hidden; height: 190rpx; border-radius: 17rpx; background: #edf0ef; }
.upload-item__image { width: 100%; height: 100%; }
.upload-item__status { position: absolute; right: 8rpx; bottom: 8rpx; padding: 6rpx 11rpx; border-radius: 999rpx; background: rgba(25,35,33,.72); color: #fff; font-size: 19rpx; }
.upload-item__status.done { background: rgba(17,107,91,.88); }
</style>
