export function queryValue(value: string | string[] | undefined): string {
  return Array.isArray(value) ? value[0] ?? '' : value ?? ''
}

export function go(path: string, params?: Record<string, string | undefined>): void {
  const search = Object.entries(params ?? {})
    .filter(([, value]) => value !== undefined)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value ?? '')}`)
    .join('&')
  uni.navigateTo({ url: search ? `${path}?${search}` : path })
}
