export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

export class HttpStatusError extends Error {
  constructor(readonly status: number, message: string, readonly code?: string) {
    super(message)
    this.name = 'HttpStatusError'
  }
}

export function bearerHeader(): Record<string, string> {
  const token = uni.getStorageSync('access-token') as string | undefined
  return token ? { Authorization: `Bearer ${token}` } : {}
}

/** Shared transport: HTTP failures remain visible and never fall back to Mock. */
export function requestJson<TResponse, TRequest extends object = never>(
  baseUrl: string,
  path: string,
  method: HttpMethod = 'GET',
  data?: TRequest,
  header?: Record<string, string>,
  timeoutMs?: number,
): Promise<TResponse> {
  return new Promise((resolve, reject) => {
    uni.request({
      url: `${baseUrl.replace(/\/$/, '')}${path}`,
      method: method as UniApp.RequestOptions['method'],
      ...(data === undefined ? {} : { data }),
      ...(timeoutMs ? { timeout: timeoutMs } : {}),
      header: { 'content-type': 'application/json', ...bearerHeader(), ...header },
      success: (response) => {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve(response.data as TResponse)
          return
        }
        const problem = response.data as {
          detail?: string; title?: string; code?: string; errorCode?: string; extensions?: { code?: string }
        } | undefined
        reject(new HttpStatusError(response.statusCode,
          problem?.detail ?? problem?.title ?? `请求失败（${response.statusCode}）`,
          problem?.code ?? problem?.errorCode ?? problem?.extensions?.code))
      },
      fail: (error) => reject(new Error(error.errMsg)),
    })
  })
}

export function isQuotaExceeded(error: unknown): boolean {
  return error instanceof HttpStatusError && error.status === 429 && error.code === 'AI_DAILY_QUOTA_EXCEEDED'
}

/** A rejected creation can start a new intent; timeouts/rate limits/conflicts may still need recovery. */
export function isDefinitiveRejection(error: unknown): boolean {
  return error instanceof HttpStatusError && (
    (error.status >= 400 && error.status < 500 && ![408, 409, 429].includes(error.status))
    || (error.status === 409 && error.code === 'PAYMENT_IDEMPOTENCY_CONFLICT')
  )
}
