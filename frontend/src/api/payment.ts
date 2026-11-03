import type { Entitlement, Order, Plan, Quota } from '@/types/payment'
import { requestJson } from './http-client'
import { MockPaymentApi } from './payment-mock'

export interface PaymentApi {
  listPlans(): Promise<Plan[]>
  createOrder(planCode: string, idempotencyKey: string): Promise<Order>
  listOrders(limit?: number): Promise<Order[]>
  getOrder(orderId: string): Promise<Order>
  mockPay(orderId: string): Promise<Order>
  getEntitlement(): Promise<Entitlement>
  getQuota(): Promise<Quota>
}

const PREFIX = '/api/payment/v1'

export class HttpPaymentApi implements PaymentApi {
  constructor(private readonly baseUrl: string) {}
  listPlans(): Promise<Plan[]> { return requestJson(this.baseUrl, `${PREFIX}/plans`) }
  listOrders(limit = 20): Promise<Order[]> { return requestJson(this.baseUrl, `${PREFIX}/orders?limit=${limit}`) }
  getOrder(orderId: string): Promise<Order> { return requestJson(this.baseUrl, `${PREFIX}/orders/${encodeURIComponent(orderId)}`) }
  getEntitlement(): Promise<Entitlement> { return requestJson(this.baseUrl, `${PREFIX}/entitlement`) }
  getQuota(): Promise<Quota> { return requestJson(this.baseUrl, '/api/ai/v1/quota') }
  createOrder(planCode: string, idempotencyKey: string): Promise<Order> {
    return requestJson(this.baseUrl, `${PREFIX}/orders`, 'POST', { planCode }, { 'X-Idempotency-Key': idempotencyKey })
  }
  mockPay(orderId: string): Promise<Order> {
    return requestJson(this.baseUrl, `${PREFIX}/orders/${encodeURIComponent(orderId)}/mock-pay`, 'POST')
  }
}

export const isMockMode = (import.meta.env.VITE_API_MODE ?? 'mock') !== 'http'
export const paymentApi: PaymentApi = isMockMode
  ? new MockPaymentApi()
  : new HttpPaymentApi(import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080')
