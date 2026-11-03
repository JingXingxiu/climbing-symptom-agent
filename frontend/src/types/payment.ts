export interface Plan {
  planCode: string
  name: string
  priceCents: number
  currency: 'CNY'
  durationDays: number
  dailyModelCallLimit: number
}

export interface Order {
  orderId: string
  planCode: string
  planName: string
  priceCents: number
  currency: 'CNY'
  durationDays: number
  dailyModelCallLimit: number
  status: 'PENDING' | 'PAID'
  expiresAt: string
  paidAt: string | null
  createdAt: string
  updatedAt: string
}

export interface Entitlement {
  userId: string
  planCode: string
  planName: string
  dailyModelCallLimit: number
  validUntil: string | null
}

export interface Quota {
  userId: string
  usageDate: string
  zoneId: 'Asia/Shanghai'
  planCode: string
  usedModelCalls: number
  dailyModelCallLimit: number
  remainingModelCalls: number
  entitlementValidUntil: string | null
}
