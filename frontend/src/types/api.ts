export type ReviewStatus = 'unreviewed' | 'in_review' | 'reviewed'
export type AnswerStatus = 'answered' | 'partially_answered' | 'not_found'
export type EvidenceType = 'verbal_claim' | 'specification_display' | 'operational_evidence' | 'unknown'
export type EvidenceSufficiency = 'not_checked' | 'insufficient' | 'sufficient'
export type TodoStatus = 'open' | 'in_progress' | 'done' | 'cancelled'
export type TodoSource = 'manual' | 'derived_from_case'

export interface Dataset {
  id: string
  label: string
  count: number
}

export interface CaseListItem {
  id: string
  ds: string
  question: string | null
  claim: string | null
  video: string | null
  /** 归属素材 id；历史数据已回填为内置素材 */
  materialId: string | null
  /** 来源：sample（内置示例）/ manual（手动新增）/ csv（批量导入） */
  source: string | null
  startSec: number | null
  endSec: number | null
  reviewStatus: ReviewStatus
  answerStatus: AnswerStatus
  evidenceType: EvidenceType
  evidenceSufficiency: EvidenceSufficiency
  followUpRequired: boolean
  createdAt: string | null
  updatedAt: string | null
}

export interface CaseDetail extends CaseListItem {
  legacyEvidenceStatus: string | null
  reasoning: string | null
  limitations: string | null
  /** 人工标记的关联时间点（秒，升序）；与 startSec/endSec 无关，未标记为空数组 */
  timePoints: number[]
  frames: string[]
}

/** PATCH /api/cases/{id} 请求体：只提交发生变化的字段。 */
export interface CasePatchPayload {
  reviewStatus?: ReviewStatus
  answerStatus?: AnswerStatus
  evidenceType?: EvidenceType
  evidenceSufficiency?: EvidenceSufficiency
  reasoning?: string
  limitations?: string
  /** 不传=保持原值；[]=清空；数组=整体替换（后端升序去重） */
  timePoints?: number[]
}

export interface Todo {
  id: number
  caseId: string
  title: string
  detail: string | null
  status: TodoStatus
  source: TodoSource
  createdAt: string
  updatedAt: string
  completedAt: string | null
}

export interface TodoCreatePayload {
  caseId: string
  title: string
  detail?: string | null
}

export interface TodoUpdatePayload {
  title?: string
  detail?: string | null
  status?: TodoStatus
}

export interface SubtitleCue {
  index: number
  startSec: number
  endSec: number
  text: string
}

/**
 * 字幕来源。
 * local = 本地 SRT 解析；cloud = 保利威智能字幕。
 * 两者 cue 结构一致，来源只描述「文字从哪来」，不代表可信度。
 */
export type SubtitleSource = 'local' | 'cloud'

/** 统一 cue：在原始 cue 上补一个来源标注，面板与高亮不必再区分端点。 */
export interface UnifiedCue extends SubtitleCue {
  source: SubtitleSource
}

export interface SubtitleResponse {
  video: string
  subtitleFile: string
  language?: string
  cueCount: number
  cues: SubtitleCue[]
}

/* ---------- 素材库 ---------- */

/** local = 内置示例（走 /media 与 FFmpeg）；polyv = 保利威点播 */
export type MaterialStorage = 'local' | 'polyv'
export type MaterialStatus = 'transcoding' | 'ready' | 'failed' | 'deleted'

/** 素材对外视图：不含任何绝对路径、目录或密钥。 */
export interface Material {
  id: string
  storage: MaterialStorage
  /** 仅用于展示，永不参与路径拼接 */
  originalName: string | null
  fileName: string | null
  builtin: boolean
  status: MaterialStatus
  /** 保利威原始状态码；本地素材为 null */
  polyvStatus: number | null
  /** 保利威是否已发布；status=50 等待审核时为 false，但 playUrl 已可播 */
  published: boolean
  durationSec: number | null
  sizeBytes: number | null
  width: number | null
  height: number | null
  srtFileName: string | null
  createdAt: string | null
}

export interface MaterialCapabilities {
  /** 上传能力是否就绪（缺凭据时为 false，前端据此禁用按钮而不是让用户点了才失败） */
  uploadEnabled: boolean
  vodConfigured: boolean
  maxBytes: number
  maxCount: number
}

/** 播放信息：playUrl 每次实时取，不落库 */
export interface MaterialPlay {
  materialId: string
  playUrl: string
  durationSec: number | null
  published: boolean
}

/* ---------- 核验事项创建与批量导入 ---------- */

export interface CaseCreatePayload {
  materialId: string
  question: string
  claim: string
  startSec?: number | null
  endSec?: number | null
  reasoning?: string | null
  limitations?: string | null
  timePoints?: number[] | null
}

export interface CaseImportRow {
  /** CSV 行号（含表头，从 1 开始） */
  line: number
  question: string | null
  claim: string | null
  startSec: number | null
  endSec: number | null
  /** 空数组表示该行可以导入 */
  problems: string[]
}

export interface CaseImportPreview {
  materialId: string
  totalRows: number
  validCount: number
  invalidCount: number
  duplicateCount: number
  rows: CaseImportRow[]
  headers: string[]
  unmappedColumns: string[]
}

export interface CaseImportSkippedRow {
  line: number
  problems: string[]
}

export type CaseImportPolicy = 'strict' | 'skip_invalid'

export interface CaseImportResult {
  materialId: string
  imported: number
  skippedCount: number
  skipped: CaseImportSkippedRow[]
  created: CaseListItem[]
  policy: CaseImportPolicy
}

/* ---------- 保利威智能字幕 ---------- */

export type SmartSubtitleStatus = 'created' | 'processing' | 'ready' | 'failed' | 'not_found'

export interface SmartSubtitleStatusInfo {
  configured: boolean
  baseUrlHost: string
  defaultLanguage: string
  allowedLanguages: string[]
  videos: {
    video: string
    vid: string | null
    durationSec: number | null
    vidConfigured: boolean
  }[]
  note: string
}

export interface SmartSubtitleTask {
  id: number
  video: string
  vid: string
  taskId: string | null
  language: string
  status: SmartSubtitleStatus
  videoDurationSec: number | null
  subtitleCount: number | null
  createdAt: string
  lastCheckedAt: string | null
  lastSuccessAt: string | null
  errorCode: string | null
  errorMessage: string | null
  /** 只在创建/登记接口返回：本次是否复用了既有任务 */
  reused: boolean | null
}

export interface SmartSubtitleCues {
  video: string
  vid: string
  taskId: string
  /** 恒为 cloud */
  source: SubtitleSource
  language: string
  status: SmartSubtitleStatus
  subtitleName: string | null
  totalCueCount: number
  cueCount: number
  windowFromSec: number | null
  windowToSec: number | null
  cues: SubtitleCue[]
  fetchedAt: string | null
}

/* ---------- AI 辅助核验（建议，不是结论） ---------- */

/** 后端 AI 能力状态：只读，不触发模型调用 */
export interface AiStatus {
  configured: boolean
  model: string
  endpointHost: string
  visionEnabled: boolean
  ffmpegAvailable: boolean
  /** 保利威点播上传能力是否就绪（决定能否新建云端素材） */
  polyvUpload: boolean
  /** 保利威截图能力是否就绪（决定云端素材能否做画面核验） */
  polyvSnapshot: boolean
  maxFrames: number
  note: string
}

export interface AiCueRef {
  index: number
  startSec: number
  endSec: number
  text: string
  outsideWindow: boolean
}

export interface AiFrameRef {
  sec: number
  width: number | null
  height: number | null
  bytes: number | null
  dataUrl: string | null
}

export interface AiEvidenceItem {
  startSec: number
  endSec: number
  subtitle: string | null
  observation: string | null
  supports: string
  cannotProve: string
  outsideWindow: boolean
  frameSec: number | null
}

/** 注意：不含 reviewStatus —— 流程进度只能由人工控制 */
export interface AiSuggestion {
  summary: string
  answerStatus: AnswerStatus
  evidenceType: EvidenceType
  evidenceSufficiency: EvidenceSufficiency
  reasoning: string
  limitations: string
  evidenceItems: AiEvidenceItem[]
  suggestedTimePoints: number[]
  unresolvedQuestions: string[]
}

export interface AiContextInfo {
  video: string | null
  /** 本次定位所用的字幕来源 */
  subtitleSource: SubtitleSource
  durationSec: number | null
  subtitleFile: string | null
  totalCueCount: number
  cuesUsed: AiCueRef[]
  frames: AiFrameRef[]
  visionUsed: boolean
  frameNote: string | null
  outsideWindowSearched: boolean
  model: string
  promptChars: number
  promptTokens: number | null
  completionTokens: number | null
  elapsedMs: number
}

export interface AiReviewResponse {
  caseId: string
  suggestion: AiSuggestion
  context: AiContextInfo
  normalizationNotes: string[]
  generatedAt: string
}

/**
 * 人工勾选后写入编辑态的部分建议。
 * 故意不含 reviewStatus：流程进度只能由人工在核验面板里自行切换。
 */
export interface AiAdoptPayload {
  answerStatus?: AnswerStatus
  evidenceType?: EvidenceType
  evidenceSufficiency?: EvidenceSufficiency
  reasoning?: string
  limitations?: string
  timePoints?: number[]
}

/* ---------- 复盘报告 ---------- */

export interface ReportStats {
  total: number
  reviewed: number
  inReview: number
  unreviewed: number
  answered: number
  partial: number
  notFound: number
  operational: number
  specificationDisplay: number
  verbalClaim: number
  evidenceUnknown: number
  sufficient: number
  insufficient: number
  notChecked: number
  todoTotal: number
  todoOpen: number
  todoInProgress: number
  todoDone: number
  todoCancelled: number
}

export interface ReviewReport {
  datasetId: string
  datasetLabel: string
  generatedAt: string
  stats: ReportStats
  aiUsed: boolean
  aiModel: string | null
  notes: string[]
  markdown: string
}

export interface ApiErrorDetail {
  field: string
  message: string
}

export interface ApiErrorBody {
  error: {
    code: string
    message: string
    details?: ApiErrorDetail[]
  }
}
