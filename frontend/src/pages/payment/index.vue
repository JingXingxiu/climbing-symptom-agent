<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { isMockMode, paymentApi } from '@/api/payment'
import { HttpStatusError, isDefinitiveRejection } from '@/api/http-client'
import type { Entitlement, Order, Plan, Quota } from '@/types/payment'
import { createUuid } from '@/utils/id'

const STORAGE_KEY = `climbing-payment-intent-v1:${isMockMode ? 'mock' : 'http'}`
interface PurchaseIntent { planCode?: string; key?: string; orderId?: string; uncertain?: boolean }
function readIntent(): PurchaseIntent {
  try {
    const cached = uni.getStorageSync(STORAGE_KEY) as string | undefined
    const parsed = cached ? JSON.parse(cached) as PurchaseIntent | null : null
    if (!parsed || typeof parsed !== 'object') return {}
    if (typeof parsed.key === 'string' && typeof parsed.planCode === 'string') {
      return { key: parsed.key, planCode: parsed.planCode }
    }
    return typeof parsed.orderId === 'string' ? { orderId: parsed.orderId, uncertain: Boolean(parsed.uncertain) } : {}
  } catch { return {} }
}
const intent = ref<PurchaseIntent>(readIntent())
const plans = ref<Plan[]>([])
const entitlement = ref<Entitlement>()
const quota = ref<Quota>()
const orders = ref<Order[]>([])
const selected = ref<Order>()
const loading = ref(false)
const busy = ref(false)
const loadErrors = ref<string[]>([])
const actionError = ref('')
const actionNotice = ref('')
const needsOrderRefresh = ref(Boolean(intent.value.uncertain))
const expired = computed(() => selected.value?.status === 'PENDING' && Date.parse(selected.value.expiresAt) <= Date.now())

function saveIntent(value: PurchaseIntent): void {
  intent.value = value
  uni.setStorageSync(STORAGE_KEY, JSON.stringify(value))
}
function money(cents: number): string { return `¥${(cents / 100).toFixed(2)}` }
function date(value: string | null): string {
  if (!value) return '长期有效'
  return new Date(Date.parse(value) + 8 * 3_600_000).toISOString().slice(0, 16).replace('T', ' ')
}
function message(error: unknown): string {
  if (error instanceof HttpStatusError && error.status === 403
    && (error.code === 'MOCK_PAYMENT_DISABLED' || /MOCK_PAYMENT_DISABLED/.test(error.message))) {
    return '当前环境未启用模拟支付（MOCK_PAYMENT_DISABLED）。仅开发环境开启模拟支付后可确认，不会扣款。'
  }
  return error instanceof Error ? error.message : '请求失败，请重试'
}
function upsertOrder(order: Order): void {
  orders.value = [order, ...orders.value.filter((item) => item.orderId !== order.orderId)]
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
}
function acceptOrder(order: Order): void {
  selected.value = order
  needsOrderRefresh.value = false
  saveIntent({ orderId: order.orderId })
  upsertOrder(order)
}

async function load(): Promise<void> {
  if (loading.value || busy.value) return
  loading.value = true
  loadErrors.value = []
  const results = await Promise.allSettled([
    paymentApi.listPlans(), paymentApi.getEntitlement(), paymentApi.getQuota(), paymentApi.listOrders(20),
  ])
  const labels = ['套餐', '当前权益', '今日额度', '订单记录']
  results.forEach((result, index) => {
    if (result.status === 'rejected') loadErrors.value.push(`${labels[index]}加载失败：${message(result.reason)}`)
  })
  if (results[0].status === 'fulfilled') plans.value = results[0].value
  if (results[1].status === 'fulfilled') entitlement.value = results[1].value
  if (results[2].status === 'fulfilled') quota.value = results[2].value
  if (results[3].status === 'fulfilled') orders.value = results[3].value
  if (intent.value.orderId) {
    try { acceptOrder(await paymentApi.getOrder(intent.value.orderId)) }
    catch (error) {
      needsOrderRefresh.value = true
      loadErrors.value.push(`订单状态查询失败：${message(error)}`)
    }
  }
  loading.value = false
}

async function refreshBenefits(): Promise<void> {
  const results = await Promise.allSettled([paymentApi.getEntitlement(), paymentApi.getQuota()])
  if (results[0].status === 'fulfilled') entitlement.value = results[0].value
  else loadErrors.value.push(`权益刷新失败：${message(results[0].reason)}`)
  if (results[1].status === 'fulfilled') quota.value = results[1].value
  else loadErrors.value.push(`额度刷新失败：${message(results[1].reason)}`)
}

async function buy(planCode: string): Promise<void> {
  if (busy.value || loading.value) return
  if (intent.value.key && intent.value.planCode !== planCode) return
  busy.value = true
  actionError.value = ''
  actionNotice.value = ''
  if (!intent.value.key) saveIntent({ planCode, key: createUuid() })
  try {
    const order = await paymentApi.createOrder(planCode, intent.value.key!)
    acceptOrder(order)
    actionNotice.value = order.status === 'PAID' ? '已恢复已支付订单。' : '订单已创建，请核对订单金额后确认模拟支付。'
  } catch (error) {
    if (isDefinitiveRejection(error)) {
      saveIntent(selected.value ? { orderId: selected.value.orderId } : {})
      actionError.value = `${message(error)}。服务端已拒绝此次购买请求，可以重新选择套餐并创建订单。`
    } else {
      // Keep the same persisted key/body when the response is uncertain.
      actionError.value = `${message(error)}。购买请求已保留，重试将使用同一请求，不会另建重复订单。`
    }
  } finally { busy.value = false }
}

async function openOrder(orderId: string): Promise<void> {
  if (busy.value || loading.value || intent.value.key) return
  busy.value = true
  actionError.value = ''
  actionNotice.value = ''
  saveIntent({ orderId, uncertain: true })
  needsOrderRefresh.value = true
  try {
    const order = await paymentApi.getOrder(orderId)
    acceptOrder(order)
    if (order.status === 'PAID') await refreshBenefits()
  } catch (error) { actionError.value = `订单状态尚未确认：${message(error)}。请重新查询。` }
  finally { busy.value = false }
}

async function pay(): Promise<void> {
  const order = selected.value
  if (!order || busy.value || loading.value || needsOrderRefresh.value || order.status === 'PAID') return
  busy.value = true
  actionError.value = ''
  actionNotice.value = ''
  try {
    // Always read the server's current order before attempting confirmation.
    const current = await paymentApi.getOrder(order.orderId)
    acceptOrder(current)
    if (current.status === 'PAID') {
      actionNotice.value = '此订单已支付，权益不会重复发放。'
    } else if (Date.parse(current.expiresAt) <= Date.now()) {
      actionError.value = '订单已过期，请重新创建订单。'
      return
    } else {
      const confirmed = await paymentApi.mockPay(current.orderId)
      acceptOrder(confirmed)
      actionNotice.value = confirmed.status === 'PAID' ? '模拟支付已确认，未发生真实扣款。' : '订单仍待支付，请查询订单状态。'
    }
    await refreshBenefits()
  } catch (error) {
    actionError.value = message(error)
    needsOrderRefresh.value = true
    saveIntent({ orderId: order.orderId, uncertain: true })
    try {
      const recovered = await paymentApi.getOrder(order.orderId)
      acceptOrder(recovered)
      if (recovered.status === 'PAID') {
        actionError.value = ''
        actionNotice.value = '已查询确认订单支付成功，未发生真实扣款。'
        await refreshBenefits()
      }
    } catch {
      actionError.value += '。订单状态尚未确认，请先查询订单；页面不会假定已支付。'
    }
  } finally { busy.value = false }
}

onShow(load)
</script>

<template>
  <view class="page">
    <view class="hero">
      <text class="eyebrow">PLANS · DAILY QUOTA</text>
      <text class="title">套餐与额度</text>
      <text class="subtitle">按模型执行尝试次数计量。每次记录可能包含多次调用，单个问答会话仍有 20 次上限。</text>
    </view>
    <view class="notice demo-notice">模拟购买演示 · 不会发起微信支付，不会真实扣款。订单金额与支付状态以服务端返回为准。</view>

    <view v-if="loading" class="card loading-card">正在加载套餐、权益与订单…</view>
    <view v-if="loadErrors.length" class="card error-card">
      <text v-for="item in loadErrors" :key="item" class="block">{{ item }}</text>
      <button class="secondary-button compact-button" :disabled="busy || loading" @tap="load">重新加载</button>
    </view>

    <view class="card quota-card">
      <view class="section-row">
        <text class="card-title">{{ entitlement?.planName || '当前权益待查询' }}</text>
        <text class="tag">{{ entitlement?.planCode || '—' }}</text>
      </view>
      <view v-if="quota" class="quota-numbers">
        <view><text class="quota-number">{{ quota.remainingModelCalls }}</text><text class="small">今日剩余</text></view>
        <view><text class="quota-number quota-number--muted">{{ quota.usedModelCalls }} / {{ quota.dailyModelCallLimit }}</text><text class="small">已用 / 每日模型执行次数</text></view>
      </view>
      <text v-else class="card-subtitle">额度暂不可用，请重新加载查询。</text>
      <text v-if="quota" class="card-subtitle">{{ quota.usageDate }} · {{ quota.zoneId }}，每日零点重置</text>
      <text v-if="entitlement" class="card-subtitle">权益有效期：{{ date(entitlement.validUntil) }}{{ entitlement.validUntil ? '（北京时间）' : '' }}</text>
      <text class="card-subtitle">续购从现有有效期后叠加；购买当日已用次数保留。权益到期后恢复 FREE 每日 10 次。</text>
    </view>

    <view v-if="intent.key" class="notice demo-notice">
      <text class="block">有一笔 {{ intent.planCode }} 购买请求等待恢复。</text>
      <button class="secondary-button compact-button" :loading="busy" :disabled="busy || loading" @tap="buy(intent.planCode!)">恢复购买请求</button>
    </view>
    <view v-for="plan in plans" :key="plan.planCode" :class="['card plan-card', { 'plan-card--plus': plan.priceCents > 0 }]">
      <view class="section-row"><text class="card-title">{{ plan.name }}</text><text class="tag">{{ plan.planCode }}</text></view>
      <text class="plan-price">{{ money(plan.priceCents) }}<text class="price-period">{{ plan.durationDays ? ` / ${plan.durationDays} 天` : ' / 长期' }}</text></text>
      <text class="card-subtitle">每天 {{ plan.dailyModelCallLimit }} 次模型执行尝试</text>
      <button v-if="plan.priceCents > 0" class="primary-button purchase-button" :disabled="busy || loading || Boolean(intent.key)" @tap="buy(plan.planCode)">创建模拟订单</button>
      <text v-else class="card-subtitle">免费基础额度，无需购买。</text>
    </view>

    <view v-if="actionError" class="notice error-message">{{ actionError }}</view>
    <view v-if="actionNotice" class="notice success-message">{{ actionNotice }}</view>
    <view v-if="selected" class="card order-detail">
      <view class="section-row"><text class="card-title">当前订单</text><text :class="['tag', { 'tag--paid': selected.status === 'PAID' }]">{{ selected.status === 'PAID' ? '已支付' : expired ? '已过期' : '待支付' }}</text></view>
      <text class="order-name">{{ selected.planName }} · {{ money(selected.priceCents) }}</text>
      <text class="card-subtitle">{{ selected.durationDays }} 天 · 每日 {{ selected.dailyModelCallLimit }} 次</text>
      <text class="small order-id">{{ selected.orderId }}</text>
      <text class="card-subtitle">创建于 {{ date(selected.createdAt) }}</text>
      <text class="card-subtitle">{{ selected.status === 'PAID' ? `支付于 ${date(selected.paidAt)}` : `支付截止 ${date(selected.expiresAt)}（北京时间）` }}</text>
      <button v-if="selected.status === 'PENDING' && !expired" class="primary-button purchase-button" :loading="busy" :disabled="busy || loading || needsOrderRefresh || Boolean(intent.key)" @tap="pay">确认模拟支付 {{ money(selected.priceCents) }} · 不扣款</button>
      <text v-if="selected.status === 'PAID'" class="card-subtitle">此订单已完成，重复查询不会重复发放权益。</text>
      <button class="secondary-button compact-button" :disabled="busy || loading || Boolean(intent.key)" @tap="openOrder(selected.orderId)">查询订单状态</button>
    </view>
    <view v-else-if="intent.orderId && needsOrderRefresh" class="card">
      <text class="card-subtitle">有一笔订单状态待确认，请查询后继续。</text>
      <button class="secondary-button compact-button" :disabled="busy || loading" @tap="openOrder(intent.orderId!)">查询待确认订单</button>
    </view>

    <view class="section-row order-heading"><text class="card-title">最近订单</text><text class="refresh-link" @tap="load">刷新</text></view>
    <view v-if="!orders.length && !loading" class="card empty">暂无订单记录</view>
    <view v-for="order in orders" :key="order.orderId" class="card order-record" @tap="openOrder(order.orderId)">
      <view class="section-row"><text class="order-name">{{ order.planName }}</text><text class="order-amount">{{ money(order.priceCents) }}</text></view>
      <view class="section-row record-bottom"><text>{{ date(order.createdAt) }}</text><text class="refresh-link">{{ order.status === 'PAID' ? '已支付 · 查看' : Date.parse(order.expiresAt) <= Date.now() ? '已过期 · 查看' : '待支付 · 继续' }} →</text></view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.demo-notice { margin-bottom: 24rpx; }
.block { display: block; }
.section-row { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.tag { flex-shrink: 0; padding: 8rpx 15rpx; border-radius: 999rpx; background: #edf2f0; color: #536b64; font-size: 21rpx; font-weight: 650; }
.tag--paid { background: var(--primary-soft); color: var(--primary); }
.quota-card { background: #1d3e38; color: #fff; }
.quota-card .card-title { color: #fff; }
.quota-card .card-subtitle, .quota-card .small { color: #c0d4ce; }
.quota-numbers { display: flex; justify-content: space-between; gap: 24rpx; margin: 28rpx 0 24rpx; }
.quota-number { display: block; font-size: 62rpx; font-weight: 750; }
.quota-number--muted { font-size: 42rpx; line-height: 1.5; }
.small { display: block; color: var(--muted); font-size: 21rpx; line-height: 1.5; }
.plan-card--plus { border-color: #83b9aa; background: linear-gradient(130deg, #fff, #edf8f3); }
.plan-price { display: block; margin-top: 22rpx; color: var(--primary); font-size: 51rpx; font-weight: 760; }
.price-period { color: var(--muted); font-size: 24rpx; font-weight: 400; }
.purchase-button { margin-top: 27rpx; font-size: 25rpx; }
.compact-button { min-height: 72rpx; margin-top: 20rpx; font-size: 24rpx; }
.error-card { color: var(--danger); font-size: 24rpx; line-height: 1.7; }
.loading-card { color: var(--muted); font-size: 26rpx; }
.error-message, .success-message { margin-bottom: 24rpx; }
.error-message { background: #fff0ee; color: var(--danger); }
.success-message { background: var(--primary-soft); color: var(--primary); }
.order-name { display: block; margin-top: 13rpx; color: var(--ink); font-size: 29rpx; font-weight: 650; }
.order-id { margin-top: 16rpx; overflow-wrap: anywhere; }
.order-heading { margin: 38rpx 5rpx 20rpx; }
.refresh-link { color: var(--primary); font-size: 23rpx; font-weight: 650; }
.order-record { padding: 24rpx; margin-bottom: 16rpx; }
.order-record .order-name { margin: 0; }
.order-amount { color: var(--primary); font-size: 28rpx; font-weight: 700; }
.record-bottom { margin-top: 18rpx; color: var(--muted); font-size: 21rpx; }
</style>
