import type { SymptomType } from '@/types/domain'

export const SYMPTOM_OPTIONS: Array<{ value: SymptomType; label: string }> = [
  { value: 'PAIN', label: '痛' },
  { value: 'ACHING', label: '酸' },
  { value: 'DISTENSION', label: '胀' },
  { value: 'NUMBNESS', label: '麻' },
  { value: 'SWELLING', label: '肿' },
  { value: 'WEAKNESS', label: '无力' },
  { value: 'STIFFNESS', label: '僵硬' },
  { value: 'OTHER_UNCERTAIN', label: '其他 / 不确定' },
]

export const SYMPTOM_LABELS: Record<SymptomType, string> = Object.fromEntries(
  SYMPTOM_OPTIONS.map((item) => [item.value, item.label]),
) as Record<SymptomType, string>

export function formatSymptoms(types: SymptomType[]): string {
  return types.map((type) => SYMPTOM_LABELS[type]).join('、') || '未选择'
}
