<script setup lang="ts">
import { computed, getCurrentInstance, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as THREE from 'three-platformize'
import { OrbitControls } from 'three-platformize/examples/jsm/controls/OrbitControls'
import { GLTFLoader } from 'three-platformize/examples/jsm/loaders/GLTFLoader'
import { WechatPlatform } from 'three-platformize/src/WechatPlatform'

import type { FingerCode, HandSide, JointCode, SymptomLocation } from '@/types/domain'
import type { Hand3DSnapshotPayload } from '@/types/hand3d'

type InteractionMode = 'NAVIGATE' | 'SELECT' | 'LOCKED'

interface RegionDefinition {
  regionId: string
  label: string
  fingerCode?: FingerCode
  jointCode?: JointCode
  position: [number, number, number]
  radius?: number
}

const props = withDefaults(
  defineProps<{
    handSide: HandSide
    selectedRegionId?: string
    interactionMode?: InteractionMode
  }>(),
  { interactionMode: 'NAVIGATE', selectedRegionId: undefined },
)

const emit = defineEmits<{
  (event: 'region-change', value: SymptomLocation): void
  (event: 'snapshot', value: Hand3DSnapshotPayload): void
  (event: 'error', value: { code: string; message: string }): void
}>()

/**
 * Stable business regions are deliberately independent from GLB triangles.
 * A future replacement mesh only needs these hotspot positions recalibrated;
 * persisted regionIds and API contracts remain unchanged.
 */
const REGION_DEFINITIONS: RegionDefinition[] = [
  { regionId: 'wrist.center', label: '腕部中央', position: [0, -1.45, 0], radius: 0.3 },
  { regionId: 'palm.center', label: '掌心', position: [0, -0.45, 0.32], radius: 0.38 },
  { regionId: 'dorsum.center', label: '手背中央', position: [0, -0.35, -0.32], radius: 0.38 },
  { regionId: 'thumb.mcp', label: '拇指根部', fingerCode: 'THUMB', jointCode: 'MCP', position: [0.56, -0.08, 0], radius: 0.26 },
  { regionId: 'thumb.ip', label: '拇指 IP 关节', fingerCode: 'THUMB', position: [0.8, 0.28, 0], radius: 0.24 },
  { regionId: 'thumb.distal', label: '拇指末端', fingerCode: 'THUMB', jointCode: 'TIP', position: [0.98, 0.58, 0], radius: 0.22 },
  { regionId: 'index.mcp', label: '食指根部 MCP', fingerCode: 'INDEX', jointCode: 'MCP', position: [0.43, 0.28, 0] },
  { regionId: 'index.pip', label: '食指 PIP 附近', fingerCode: 'INDEX', jointCode: 'PIP', position: [0.43, 0.86, 0] },
  { regionId: 'index.dip', label: '食指 DIP 附近', fingerCode: 'INDEX', jointCode: 'DIP', position: [0.43, 1.29, 0] },
  { regionId: 'index.distal', label: '食指末端', fingerCode: 'INDEX', jointCode: 'TIP', position: [0.43, 1.61, 0] },
  { regionId: 'middle.mcp', label: '中指根部 MCP', fingerCode: 'MIDDLE', jointCode: 'MCP', position: [0.04, 0.3, 0] },
  { regionId: 'middle.pip', label: '中指 PIP 附近', fingerCode: 'MIDDLE', jointCode: 'PIP', position: [0.04, 0.94, 0] },
  { regionId: 'middle.dip', label: '中指 DIP 附近', fingerCode: 'MIDDLE', jointCode: 'DIP', position: [0.04, 1.42, 0] },
  { regionId: 'middle.distal', label: '中指末端', fingerCode: 'MIDDLE', jointCode: 'TIP', position: [0.04, 1.78, 0] },
  { regionId: 'ring.mcp', label: '无名指根部 MCP', fingerCode: 'RING', jointCode: 'MCP', position: [-0.34, 0.27, 0] },
  { regionId: 'ring.pip', label: '无名指 PIP 附近', fingerCode: 'RING', jointCode: 'PIP', position: [-0.34, 0.84, 0] },
  { regionId: 'ring.dip', label: '无名指 DIP 附近', fingerCode: 'RING', jointCode: 'DIP', position: [-0.34, 1.25, 0] },
  { regionId: 'ring.distal', label: '无名指末端', fingerCode: 'RING', jointCode: 'TIP', position: [-0.34, 1.55, 0] },
  { regionId: 'little.mcp', label: '小指根部 MCP', fingerCode: 'LITTLE', jointCode: 'MCP', position: [-0.69, 0.2, 0] },
  { regionId: 'little.pip', label: '小指 PIP 附近', fingerCode: 'LITTLE', jointCode: 'PIP', position: [-0.69, 0.67, 0] },
  { regionId: 'little.dip', label: '小指 DIP 附近', fingerCode: 'LITTLE', jointCode: 'DIP', position: [-0.69, 1.0, 0] },
  { regionId: 'little.distal', label: '小指末端', fingerCode: 'LITTLE', jointCode: 'TIP', position: [-0.69, 1.27, 0] },
]

function businessRegionId(region: RegionDefinition, side: HandSide = props.handSide) {
  return `hand.${side.toLowerCase()}.${region.regionId}`
}

const instance = getCurrentInstance()
const loadingState = ref<'LOADING' | 'READY' | 'FALLBACK' | 'ERROR'>('LOADING')
const selectedRegion = ref<RegionDefinition | undefined>(
  REGION_DEFINITIONS.find((region) => businessRegionId(region) === props.selectedRegionId),
)
const capturing = ref(false)

let canvasNode: any
let platform: WechatPlatform | undefined
let renderer: THREE.WebGLRenderer | undefined
let scene: THREE.Scene | undefined
let camera: THREE.PerspectiveCamera | undefined
let controls: OrbitControls | undefined
let handRoot: THREE.Group | undefined
let hotspotRoot: THREE.Group | undefined
let animationFrame = 0
let canvasWidth = 0
let canvasHeight = 0
let pointerStart: { x: number; y: number; at: number } | undefined

const selectedLabel = computed(() => selectedRegion.value?.label ?? '尚未选择粗略位置')

function asTouch(event: any, preferChanged = false) {
  const nativeEvent = event?.detail?.touches ? event.detail : event
  const list = preferChanged ? nativeEvent?.changedTouches : nativeEvent?.touches
  const touch = list?.[0] ?? nativeEvent?.changedTouches?.[0]
  if (!touch) return undefined
  return {
    x: Number(touch.x ?? touch.clientX ?? touch.pageX ?? 0),
    y: Number(touch.y ?? touch.clientY ?? touch.pageY ?? 0),
  }
}

function forwardTouch(event: any) {
  if (!platform || props.interactionMode === 'LOCKED') return
  const nativeEvent = event?.detail?.touches ? event.detail : event
  // OrbitControls expects a pointer-up equivalent even when WeChat cancels a
  // touch (for example when an OS gesture interrupts a pinch).
  platform.dispatchTouchEvent(nativeEvent?.type === 'touchcancel'
    ? { ...nativeEvent, type: 'touchend' }
    : nativeEvent)
}

function handleTouchStart(event: any) {
  const touch = asTouch(event)
  if (touch) pointerStart = { ...touch, at: Date.now() }
  forwardTouch(event)
}

function handleTouchMove(event: any) {
  forwardTouch(event)
}

function handleTouchEnd(event: any) {
  forwardTouch(event)
  const touch = asTouch(event, true)
  const start = pointerStart
  pointerStart = undefined
  if (!touch || !start || props.interactionMode !== 'SELECT') return
  const moved = Math.hypot(touch.x - start.x, touch.y - start.y)
  if (moved <= 10 && Date.now() - start.at <= 450) selectRegionAt(touch.x, touch.y)
}

function selectRegionAt(x: number, y: number) {
  if (!camera || !hotspotRoot || !canvasWidth || !canvasHeight) return
  const pointer = new THREE.Vector2((x / canvasWidth) * 2 - 1, -(y / canvasHeight) * 2 + 1)
  const raycaster = new THREE.Raycaster()
  raycaster.setFromCamera(pointer, camera)
  const hit = raycaster.intersectObjects(hotspotRoot.children, false)[0]
  const regionId = hit?.object.userData.regionId as string | undefined
  const region = REGION_DEFINITIONS.find((item) => item.regionId === regionId)
  if (!region) return
  selectedRegion.value = region
  updateHotspotStyles()
  emit('region-change', toSymptomLocation(region))
}

function toSymptomLocation(region: RegionDefinition): SymptomLocation {
  return {
    handSide: props.handSide,
    regionId: businessRegionId(region),
    displayLabel: `${props.handSide === 'LEFT' ? '左手' : '右手'} · ${region.label}`,
    fingerCode: region.fingerCode,
    jointCode: region.jointCode,
  }
}

function addHotspots() {
  if (!handRoot) return
  hotspotRoot = new THREE.Group()
  hotspotRoot.name = 'business-region-hotspots-v1'
  REGION_DEFINITIONS.forEach((region) => {
    const geometry = new THREE.SphereGeometry(region.radius ?? 0.205, 16, 12)
    const material = new THREE.MeshBasicMaterial({
      color: 0x2c8777,
      transparent: true,
      opacity: 0.18,
      depthTest: false,
    })
    const hotspot = new THREE.Mesh(geometry, material)
    hotspot.position.set(...region.position)
    hotspot.renderOrder = 10
    hotspot.userData.regionId = region.regionId
    hotspotRoot!.add(hotspot)
  })
  handRoot.add(hotspotRoot)
  updateHotspotStyles()
}

function updateHotspotStyles(snapshotMode = false) {
  hotspotRoot?.children.forEach((object) => {
    const mesh = object as THREE.Mesh
    const material = mesh.material as THREE.MeshBasicMaterial
    const isSelected = object.userData.regionId === selectedRegion.value?.regionId
    material.color.setHex(isSelected ? 0xe0473e : 0x2c8777)
    material.opacity = isSelected ? 0.82 : 0.18
    mesh.scale.setScalar(isSelected ? 1.12 : 1)
    mesh.visible = !snapshotMode || isSelected
  })
}

function loadHandModel() {
  if (!scene) return
  const loader = new GLTFLoader()
  loader.load(
    '/static/hand/hand-lowpoly.glb',
    (gltf) => {
      handRoot = new THREE.Group()
      handRoot.name = 'hand-root'
      gltf.scene.traverse((object) => {
        const mesh = object as THREE.Mesh
        if (!mesh.isMesh) return
        mesh.material = new THREE.MeshStandardMaterial({
          color: 0xe0ae91,
          roughness: 0.86,
          metalness: 0,
        })
      })
      handRoot.add(gltf.scene)
      handRoot.scale.x = props.handSide === 'LEFT' ? -1 : 1
      scene!.add(handRoot)
      addHotspots()
      loadingState.value = 'READY'
    },
    undefined,
    (error) => {
      loadingState.value = 'ERROR'
      emit('error', { code: 'HAND_MODEL_LOAD_FAILED', message: String(error) })
    },
  )
}

function initialiseThree() {
  const query = uni.createSelectorQuery().in(instance?.proxy)
  const canvasSelector = query.select('#hand-3d-canvas') as any
  canvasSelector
    .fields({ node: true, size: true })
    .exec((result: Array<{ node?: any; width?: number; height?: number }>) => {
      const canvasInfo = result?.[0]
      if (!canvasInfo?.node || !canvasInfo.width || !canvasInfo.height) {
        loadingState.value = 'ERROR'
        emit('error', { code: 'WEBGL_CANVAS_UNAVAILABLE', message: '无法创建微信小程序 WebGL Canvas' })
        return
      }

      canvasNode = canvasInfo.node
      canvasWidth = canvasInfo.width
      canvasHeight = canvasInfo.height
      // r133 OrbitControls uses the Pointer Events capture API. Native
      // mini-program canvas does not expose it on every base-library/device.
      canvasNode.setPointerCapture ??= () => undefined
      canvasNode.releasePointerCapture ??= () => undefined
      const systemInfo = uni.getSystemInfoSync()
      const pixelRatio = Math.min(Number(systemInfo.pixelRatio || 1), 2)

      platform = new WechatPlatform(canvasNode, canvasWidth, canvasHeight).patchXHR()
      THREE.PLATFORM.set(platform)
      // WeChat's `type="webgl"` canvas only accepts a WebGL 1 context on
      // affected base-library/devtools versions. The generic renderer probes
      // `webgl2` first and fails before it can fall back.
      renderer = new THREE.WebGL1Renderer({
        canvas: canvasNode,
        antialias: false,
        alpha: false,
        preserveDrawingBuffer: true,
      })
      renderer.setPixelRatio(pixelRatio)
      renderer.setSize(canvasWidth, canvasHeight, false)
      renderer.outputEncoding = THREE.sRGBEncoding

      scene = new THREE.Scene()
      scene.background = new THREE.Color(0xf1f7f5)
      camera = new THREE.PerspectiveCamera(34, canvasWidth / canvasHeight, 0.1, 100)
      camera.position.set(0, -0.05, 6.3)

      controls = new OrbitControls(camera, canvasNode as never)
      controls.target.set(0, 0, 0)
      controls.enableDamping = true
      controls.dampingFactor = 0.08
      controls.enablePan = false
      controls.minDistance = 4.5
      controls.maxDistance = 9
      controls.enabled = props.interactionMode === 'NAVIGATE'

      scene.add(new THREE.HemisphereLight(0xffffff, 0x7b8c88, 1.2))
      const keyLight = new THREE.DirectionalLight(0xffffff, 1.0)
      keyLight.position.set(3, 5, 5)
      scene.add(keyLight)
      const fillLight = new THREE.DirectionalLight(0xc8e8df, 0.5)
      fillLight.position.set(-4, 1, 3)
      scene.add(fillLight)

      loadHandModel()
      renderLoop()
    })
}

function renderLoop() {
  if (!canvasNode || !renderer || !scene || !camera) return
  controls?.update()
  renderer.render(scene, camera)
  animationFrame = canvasNode.requestAnimationFrame(renderLoop)
}

async function persistSnapshot(tempFilePath: string): Promise<string> {
  return await new Promise((resolve) => {
    uni.saveFile({
      tempFilePath,
      success: (result) => resolve(result.savedFilePath),
      fail: () => resolve(tempFilePath),
    })
  })
}

async function captureSnapshot() {
  if (capturing.value) return
  if (!canvasNode || !renderer || !scene || !camera) {
    emit('error', { code: 'HAND_VIEW_NOT_READY', message: '3D 手部仍在准备，请稍后再试' })
    return
  }
  if (!selectedRegion.value) {
    emit('error', { code: 'HAND_REGION_REQUIRED', message: '请先轻点一个粗略区域' })
    return
  }
  capturing.value = true
  updateHotspotStyles(true)
  renderer.render(scene, camera)

  try {
    const tempFilePath = await new Promise<string>((resolve, reject) => {
      ;(uni.canvasToTempFilePath as any)({
        canvas: canvasNode,
        fileType: 'png',
        quality: 0.92,
        success: (result: { tempFilePath: string }) => resolve(result.tempFilePath),
        fail: reject,
      })
    })
    const pictureUrl = await persistSnapshot(tempFilePath)
    emit('snapshot', {
      pictureUrl,
      width: canvasNode.width,
      height: canvasNode.height,
      symptomLocations: [toSymptomLocation(selectedRegion.value)],
      viewState: {
        modelAssetVersion: 'blender-studio-cc0-hand-v1',
        camera: { x: camera.position.x, y: camera.position.y, z: camera.position.z },
        target: {
          x: controls?.target.x ?? 0,
          y: controls?.target.y ?? 0,
          z: controls?.target.z ?? 0,
        },
        zoom: camera.zoom,
        coarseRegionId: businessRegionId(selectedRegion.value),
      },
    })
  } catch (error) {
    emit('error', { code: 'HAND_SNAPSHOT_FAILED', message: String(error) })
  } finally {
    updateHotspotStyles(false)
    capturing.value = false
  }
}

function disposeThree() {
  if (canvasNode && animationFrame) canvasNode.cancelAnimationFrame(animationFrame)
  controls?.dispose()
  scene?.traverse((object) => {
    const mesh = object as THREE.Mesh
    if (!mesh.isMesh) return
    mesh.geometry?.dispose()
    const materials = Array.isArray(mesh.material) ? mesh.material : [mesh.material]
    materials.forEach((material) => material?.dispose())
  })
  renderer?.dispose()
  if (platform) THREE.PLATFORM.dispose()
  platform = undefined
  renderer = undefined
  scene = undefined
  camera = undefined
  handRoot = undefined
  hotspotRoot = undefined
  canvasNode = undefined
}

watch(
  () => props.selectedRegionId,
  (regionId) => {
    selectedRegion.value = REGION_DEFINITIONS.find((region) => businessRegionId(region) === regionId)
    updateHotspotStyles()
  },
)

watch(
  () => props.handSide,
  (handSide) => {
    if (handRoot) handRoot.scale.x = handSide === 'LEFT' ? -1 : 1
    if (selectedRegion.value) emit('region-change', toSymptomLocation(selectedRegion.value))
  },
)

watch(
  () => props.interactionMode,
  (mode) => {
    if (controls) controls.enabled = mode === 'NAVIGATE'
  },
)

onMounted(async () => {
  await nextTick()
  // The product target is mp-weixin. Other builds deliberately show the
  // standard-picture fallback instead of pretending WebGL parity.
  // #ifdef MP-WEIXIN
  initialiseThree()
  // #endif
  // #ifndef MP-WEIXIN
  loadingState.value = 'FALLBACK'
  // #endif
})

onBeforeUnmount(disposeThree)

defineExpose({ captureSnapshot })
</script>

<template>
  <view class="hand-viewer">
    <view class="hand-viewer__stage">
      <canvas
        v-if="loadingState !== 'FALLBACK'"
        id="hand-3d-canvas"
        type="webgl"
        class="hand-viewer__canvas"
        disable-scroll
        @touchstart="handleTouchStart"
        @touchmove="handleTouchMove"
        @touchend="handleTouchEnd"
        @touchcancel="handleTouchEnd"
      />
      <image
        v-else
        class="hand-viewer__fallback"
        src="/static/hand/hand-palm.png"
        mode="aspectFit"
      />

      <view v-if="loadingState === 'LOADING'" class="hand-viewer__overlay">
        <text class="hand-viewer__overlay-title">正在加载轻量手部模型…</text>
        <text class="hand-viewer__overlay-copy">首次打开需要解析约 481 KB 的本地 GLB</text>
      </view>
      <view v-else-if="loadingState === 'ERROR'" class="hand-viewer__overlay">
        <text class="hand-viewer__overlay-title">3D 暂不可用</text>
        <text class="hand-viewer__overlay-copy">仍可切换到标准图片完成位置标注</text>
      </view>
      <view v-else-if="loadingState === 'FALLBACK'" class="hand-viewer__fallback-note">
        3D 交互仅在微信小程序构建中启用
      </view>
    </view>

    <view class="hand-viewer__legend">
      <view class="hand-viewer__legend-dot" />
      <text>拖动旋转 · 双指缩放 · 轻点半透明区域选择</text>
    </view>

    <view class="hand-viewer__selection">
      <view>
        <text class="hand-viewer__selection-caption">粗略位置</text>
        <text class="hand-viewer__selection-value">{{ selectedLabel }}</text>
      </view>
      <text class="hand-viewer__side">{{ handSide === 'LEFT' ? '左手' : '右手' }}</text>
    </view>

  </view>
</template>

<style scoped lang="scss">
.hand-viewer {
  overflow: hidden;
  border: 2rpx solid #dbe8e4;
  border-radius: 28rpx;
  background: #fff;
}

.hand-viewer__stage {
  position: relative;
  height: 700rpx;
  overflow: hidden;
  background: #f1f7f5;
}

.hand-viewer__canvas,
.hand-viewer__fallback {
  width: 100%;
  height: 100%;
}

.hand-viewer__overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: rgba(241, 247, 245, 0.92);
}

.hand-viewer__overlay-title {
  color: #184f47;
  font-size: 30rpx;
  font-weight: 700;
}

.hand-viewer__overlay-copy {
  margin-top: 12rpx;
  color: #6d7f7b;
  font-size: 23rpx;
}

.hand-viewer__fallback-note {
  position: absolute;
  right: 22rpx;
  bottom: 20rpx;
  padding: 10rpx 16rpx;
  border-radius: 999rpx;
  background: rgba(36, 74, 67, 0.78);
  color: #fff;
  font-size: 21rpx;
}

.hand-viewer__legend {
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 18rpx 24rpx 4rpx;
  color: #657b76;
  font-size: 22rpx;
}

.hand-viewer__legend-dot {
  width: 18rpx;
  height: 18rpx;
  border: 5rpx solid rgba(44, 135, 119, 0.2);
  border-radius: 50%;
  background: #2c8777;
}

.hand-viewer__selection {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 20rpx 24rpx 18rpx;
  padding: 22rpx;
  border-radius: 20rpx;
  background: #f6faf9;
}

.hand-viewer__selection-caption,
.hand-viewer__selection-value {
  display: block;
}

.hand-viewer__selection-caption {
  color: #81918e;
  font-size: 21rpx;
}

.hand-viewer__selection-value {
  margin-top: 6rpx;
  color: #173e38;
  font-size: 28rpx;
  font-weight: 700;
}

.hand-viewer__side {
  padding: 8rpx 16rpx;
  border-radius: 999rpx;
  background: #dcefeb;
  color: #176b5d;
  font-size: 23rpx;
  font-weight: 650;
}

</style>
