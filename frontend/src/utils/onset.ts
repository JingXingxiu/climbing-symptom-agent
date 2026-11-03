import type { AssessmentInput } from '@/types/domain'

export function formatOnset(input: AssessmentInput): string {
  if (input.onsetText?.trim()) return input.onsetText
  if (input.onsetKind === 'TODAY') return '今天'
  if (input.onsetKind === 'DAYS_AGO') return `${input.onsetAmount ?? 1} 天前`
  if (input.onsetKind === 'WEEKS_AGO') return `${input.onsetAmount ?? 1} 周前`
  if (input.onsetKind === 'MONTHS_AGO') return `${input.onsetAmount ?? 1} 个月前`
  if (input.onsetKind === 'EXACT_DATE') return input.onsetDate || '具体日期未填写'
  return '记不清'
}
