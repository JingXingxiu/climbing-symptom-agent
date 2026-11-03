import type { Entitlement, Order, Plan, Quota } from '@/types/payment'
import { createUuid } from '@/utils/id'
import { HttpStatusError } from './http-client'

const STORAGE_KEY = 'climbing-payment-mock-state-v1'
const USER_ID = '00000000-0000-4000-8000-000000000001'
const DAY_MS = 86_400_000

export const MOCK_PLANS: Plan[] = [
  { planCode: 'PLUS', name: 'PLUS 演示套餐', priceCents: 1990, currency: 'CNY', durationDays: 30, dailyModelCallLimit: 50 },
]

interface MockPaymentState {
  orders: Order[]
  keys: Record<string, { planCode: string; orderId: string }>
  entitlement: Entitlement
  usageDate: string
  usedModelCalls: number
}

function clone<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T }

/** Use an explicit UTC+8 offset; the simulator/device timezone may differ. */
export function shanghaiDate(timestamp = Date.now()): string {
  return new Date(timestamp + 8 * 3_600_000).toISOString().slice(0, 10)
}

function freeEntitlement(): Entitlement {
  return { userId: USER_ID, planCode: 'FREE', planName: '免费版', dailyModelCallLimit: 10, validUntil: null }
}

function save(state: MockPaymentState): void {
  uni.setStorageSync(STORAGE_KEY, JSON.stringify(state))
}

function read(): MockPaymentState {
  const cached = uni.getStorageSync(STORAGE_KEY) as string | MockPaymentState | undefined
  let state: MockPaymentState = {
    orders: [], keys: {}, entitlement: freeEntitlement(), usageDate: shanghaiDate(), usedModelCalls: 0,
  }
  if (cached) {
    try {
      const parsed = typeof cached === 'string' ? JSON.parse(cached) as MockPaymentState : cached
      if (!Array.isArray(parsed.orders) || !parsed.entitlement || !parsed.keys
        || !Number.isInteger(parsed.usedModelCalls) || parsed.usedModelCalls < 0) throw new Error('invalid cache')
      state = parsed
    } catch {
      // A malformed local demo cache has no authoritative payment state.
      uni.removeStorageSync(STORAGE_KEY)
    }
  }
  if (state.usageDate !== shanghaiDate()) {
    state.usageDate = shanghaiDate()
    state.usedModelCalls = 0
  }
  if (state.entitlement.validUntil && Date.parse(state.entitlement.validUntil) <= Date.now()) {
    state.entitlement = freeEntitlement()
  }
  state.entitlement.userId = USER_ID
  save(state)
  return state
}

function quota(state: MockPaymentState): Quota {
  return {
    userId: USER_ID, usageDate: state.usageDate, zoneId: 'Asia/Shanghai', planCode: state.entitlement.planCode,
    usedModelCalls: state.usedModelCalls, dailyModelCallLimit: state.entitlement.dailyModelCallLimit,
    remainingModelCalls: Math.max(0, state.entitlement.dailyModelCallLimit - state.usedModelCalls),
    entitlementValidUntil: state.entitlement.validUntil,
  }
}

/** Reserve a model execution attempt before changing the Mock Agent state. */
export function consumeMockModelCall(): void {
  const state = read()
  if (quota(state).remainingModelCalls <= 0) {
    throw new HttpStatusError(429, '今日模型执行次数已用完，可查看套餐与额度。', 'AI_DAILY_QUOTA_EXCEEDED')
  }
  state.usedModelCalls += 1
  save(state)
}

export class MockPaymentApi {
  async listPlans(): Promise<Plan[]> { return clone(MOCK_PLANS) }
  async getEntitlement(): Promise<Entitlement> { return clone(read().entitlement) }
  async getQuota(): Promise<Quota> { return clone(quota(read())) }
  async listOrders(limit = 20): Promise<Order[]> { return clone(read().orders.slice(0, limit)) }

  async getOrder(orderId: string): Promise<Order> {
    const order = read().orders.find((item) => item.orderId === orderId)
    if (!order) throw new HttpStatusError(404, '没有找到这笔订单', 'PAYMENT_ORDER_NOT_FOUND')
    return clone(order)
  }

  async createOrder(planCode: string, idempotencyKey: string): Promise<Order> {
    const state = read()
    const existing = state.keys[idempotencyKey]
    if (existing) {
      if (existing.planCode !== planCode) throw new HttpStatusError(409, '同一购买请求不能更换套餐', 'PAYMENT_IDEMPOTENCY_CONFLICT')
      return this.getOrder(existing.orderId)
    }
    const plan = MOCK_PLANS.find((item) => item.planCode === planCode && item.priceCents > 0)
    if (!plan) throw new HttpStatusError(404, '没有找到可购买的套餐', 'PAYMENT_PLAN_NOT_FOUND')
    const timestamp = new Date().toISOString()
    const order: Order = {
      orderId: createUuid(), planCode, planName: plan.name, priceCents: plan.priceCents, currency: plan.currency,
      durationDays: plan.durationDays, dailyModelCallLimit: plan.dailyModelCallLimit, status: 'PENDING',
      expiresAt: new Date(Date.now() + 30 * 60_000).toISOString(), paidAt: null,
      createdAt: timestamp, updatedAt: timestamp,
    }
    state.orders.unshift(order)
    state.keys[idempotencyKey] = { planCode, orderId: order.orderId }
    save(state)
    return clone(order)
  }

  async mockPay(orderId: string): Promise<Order> {
    const state = read()
    const order = state.orders.find((item) => item.orderId === orderId)
    if (!order) throw new HttpStatusError(404, '没有找到这笔订单', 'PAYMENT_ORDER_NOT_FOUND')
    if (order.status === 'PAID') return clone(order)
    if (Date.parse(order.expiresAt) <= Date.now()) throw new HttpStatusError(409, '订单已过期，请重新创建订单', 'PAYMENT_ORDER_EXPIRED')
    const timestamp = new Date().toISOString()
    order.status = 'PAID'
    order.paidAt = timestamp
    order.updatedAt = timestamp
    const currentEnd = state.entitlement.validUntil ? Date.parse(state.entitlement.validUntil) : 0
    state.entitlement = {
      userId: USER_ID, planCode: order.planCode, planName: order.planName,
      dailyModelCallLimit: order.dailyModelCallLimit,
      validUntil: new Date(Math.max(Date.now(), currentEnd) + order.durationDays * DAY_MS).toISOString(),
    }
    // A purchase changes the limit; today's usage remains untouched.
    save(state)
    return clone(order)
  }
}
