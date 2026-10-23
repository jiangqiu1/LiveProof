import type {
  AnswerStatus,
  EvidenceSufficiency,
  EvidenceType,
  ReviewStatus,
  TodoStatus,
} from '@/types/api'

export interface SegmentOption<T extends string> {
  value: T
  label: string
}

/** 核验进度：只描述人工看过没有，不得由其它字段推断 */
export const REVIEW_STATUS_OPTIONS: SegmentOption<ReviewStatus>[] = [
  { value: 'unreviewed', label: '尚未检查' },
  { value: 'in_review', label: '核验中' },
  { value: 'reviewed', label: '已核验' },
]

/** 回答状态：只回答「视频里有没有这样说」，不等于内容为真 */
export const ANSWER_STATUS_OPTIONS: SegmentOption<AnswerStatus>[] = [
  { value: 'answered', label: '已回答' },
  { value: 'partially_answered', label: '部分回答' },
  { value: 'not_found', label: '未找到回答' },
]

/** 证据类型：只描述证据形态 */
export const EVIDENCE_TYPE_OPTIONS: SegmentOption<EvidenceType>[] = [
  { value: 'verbal_claim', label: '口头承诺' },
  { value: 'specification_display', label: '规格或说明展示' },
  { value: 'operational_evidence', label: '实际操作证据' },
  { value: 'unknown', label: '待确认' },
]

/** 证据充分性：与证据形态是两个独立维度 */
export const EVIDENCE_SUFFICIENCY_OPTIONS: SegmentOption<EvidenceSufficiency>[] = [
  { value: 'not_checked', label: '尚未评估' },
  { value: 'insufficient', label: '证据不足' },
  { value: 'sufficient', label: '证据充分' },
]

export const TODO_STATUS_OPTIONS: SegmentOption<TodoStatus>[] = [
  { value: 'open', label: '待处理' },
  { value: 'in_progress', label: '进行中' },
  { value: 'done', label: '已完成' },
  { value: 'cancelled', label: '已取消' },
]

export const REVIEW_STATUS_LABEL: Record<ReviewStatus, string> = {
  unreviewed: '尚未检查',
  in_review: '核验中',
  reviewed: '已核验',
}

export const ANSWER_STATUS_LABEL: Record<AnswerStatus, string> = {
  answered: '已回答',
  partially_answered: '部分回答',
  not_found: '未找到回答',
}

export const EVIDENCE_TYPE_LABEL: Record<EvidenceType, string> = {
  verbal_claim: '口头承诺',
  specification_display: '规格或说明展示',
  operational_evidence: '实际操作证据',
  unknown: '待确认',
}

export const EVIDENCE_SUFFICIENCY_LABEL: Record<EvidenceSufficiency, string> = {
  not_checked: '尚未评估',
  insufficient: '证据不足',
  sufficient: '证据充分',
}

export const TODO_STATUS_LABEL: Record<TodoStatus, string> = {
  open: '待处理',
  in_progress: '进行中',
  done: '已完成',
  cancelled: '已取消',
}

export type Tone = 'neutral' | 'mint' | 'warn' | 'ink'

export function reviewTone(status: ReviewStatus): Tone {
  if (status === 'reviewed') return 'mint'
  if (status === 'in_review') return 'ink'
  return 'neutral'
}

export function sufficiencyTone(status: EvidenceSufficiency): Tone {
  if (status === 'sufficient') return 'mint'
  if (status === 'insufficient') return 'warn'
  return 'neutral'
}

export function todoTone(status: TodoStatus): Tone {
  if (status === 'done') return 'mint'
  if (status === 'in_progress') return 'ink'
  if (status === 'cancelled') return 'neutral'
  return 'warn'
}

/** 秒 → mm:ss（字幕与时间点统一用这个格式） */
export function formatTime(sec: number | null | undefined): string {
  if (sec === null || sec === undefined || Number.isNaN(sec)) return '--:--'
  const total = Math.max(0, Math.round(sec))
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

export function formatRange(start: number | null, end: number | null): string {
  return `${formatTime(start)} - ${formatTime(end)}`
}

/** 后端 LocalDateTime 字符串（无时区）直接取到分钟展示，避免浏览器时区二次偏移 */
export function formatDateTime(value: string | null): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 16)
}
