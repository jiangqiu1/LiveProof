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
  /**
   * 回答状态。{@code null} 表示**尚未核验**（V8 起），与「核验过但视频里没找到回答」
   * （{@code not_found}）是两件不同的事，界面上不能都显示成「未找到回答」。
   */
  answerStatus: AnswerStatus | null
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

/** 字幕来源：uploaded=素材页上传并登记；mapping=内置白名单映射；legacy=素材目录回退 */
export type SubtitleFileSource = 'uploaded' | 'mapping' | 'legacy'

export interface SubtitleResponse {
  video: string
  subtitleFile: string
  /** 这份字幕是哪来的。「没上传」不等于「没有可用字幕」，展示时必须区分 */
  source: SubtitleFileSource
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
  /** 仅表示「用户为该素材上传过 SRT」的登记值；内置示例素材为 null 但可能有映射字幕 */
  srtFileName: string | null
  /**
   * 该素材当前能否解析到字幕（后端按三级顺序判断的结果）。
   *
   * <p>不要用 `srtFileName != null` 代替：内置示例素材登记值为 null，
   * 靠静态白名单映射到配套 SRT，实际有字幕。
   */
  subtitleAvailable: boolean
  createdAt: string | null
  /** 素材最后变更时间（转码状态、字幕登记都会刷新），不代表真正编辑过 */
  updatedAt: string | null
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

/* ---------- AI 候选核验事项（从字幕提取，不是核验结论） ---------- */

/**
 * 用哪份字幕提取候选。
 *
 * <p>{@code auto} 是默认值：先本地 SRT，没有再用已就绪的云端字幕；
 * 两者都没有才报「无可用字幕」。之所以不是单一来源，是因为用户通常不知道
 * 这个素材到底准备了哪一份字幕，让他先去看素材页反而多一步。
 */
export type CandidateSubtitleSource = 'auto' | 'local' | 'cloud'

/**
 * 一条候选事项。
 *
 * <p>刻意不含任何核验字段：AI 只负责「想出有哪些承诺值得核验」，
 * 是否被证明仍由人工回到工作台判断。
 */
export interface AiCaseCandidate {
  id: string
  question: string
  claim: string
  startSec: number | null
  endSec: number | null
  /** 该区间的字幕原文；服务端会用真实字幕校正模型改写的引用 */
  quote: string | null
  cueIndexes: number[]
  /** 疑似重复：库内已有同问题，或本批前面已出现 */
  duplicate: boolean
  duplicateOf: string | null
  duplicateOfIndex: number | null
}

export interface AiCandidateResponse {
  materialId: string
  subtitleSource: 'local' | 'cloud'
  subtitleFile: string | null
  totalCues: number
  cuesUsed: number
  truncated: boolean
  model: string
  elapsedMs: number
  candidates: AiCaseCandidate[]
  duplicateCount: number
  notes: string[]
}

/** 确认入库时提交的单条。只带问题、承诺与时间区间。 */
export interface CaseCandidateItem {
  question: string
  claim: string
  startSec: number | null
  endSec: number | null
}

export interface CaseCandidateBatchResult {
  materialId: string
  requested: number
  createdCount: number
  /** 写入后的事项，来源恒为 ai、核验进度恒为尚未核验 */
  created: CaseDetail[]
  source: string
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
