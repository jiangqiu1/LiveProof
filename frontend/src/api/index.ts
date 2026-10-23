import { ApiError, http } from '@/api/client'
import type {
  AiReviewResponse,
  AiStatus,
  CaseCreatePayload,
  CaseDetail,
  CaseImportPolicy,
  CaseImportPreview,
  CaseImportResult,
  CaseListItem,
  CasePatchPayload,
  Dataset,
  Material,
  MaterialCapabilities,
  MaterialPlay,
  ReviewReport,
  SmartSubtitleCues,
  SmartSubtitleStatusInfo,
  SmartSubtitleTask,
  SubtitleResponse,
  SubtitleSource,
  Todo,
  TodoCreatePayload,
  TodoUpdatePayload,
  TodoStatus,
} from '@/types/api'

/** GET /api/datasets —— 数据集与用例数 */
export async function fetchDatasets(): Promise<Dataset[]> {
  const { data } = await http.get<Dataset[]>('/api/datasets')
  return data
}

/**
 * GET /api/cases —— 用例列表（按 startSec 升序）。
 *
 * @param materialId 可选：只看某个素材下的核验事项
 */
export async function fetchCases(ds = 'cf', materialId?: string | null): Promise<CaseListItem[]> {
  const { data } = await http.get<CaseListItem[]>('/api/cases', {
    params: { ds, materialId: materialId || undefined },
  })
  return data
}

/**
 * POST /api/cases —— 手动新增核验事项。
 *
 * <p>服务端强制 reviewStatus=unreviewed；answerStatus 落库为占位值 not_found，
 * 前端必须显示为「尚未核验」，不能读成「视频没有这样说」。
 */
export async function createCase(payload: CaseCreatePayload): Promise<CaseDetail> {
  const { data } = await http.post<CaseDetail>('/api/cases', payload)
  return data
}

/**
 * DELETE /api/cases/{id} —— 删除核验事项。
 *
 * <p>有挂接待办时默认返回 409 CASE_HAS_TODOS；带 withTodos=true 才连带删除。
 */
export async function deleteCase(id: string, withTodos = false): Promise<void> {
  await http.delete(`/api/cases/${encodeURIComponent(id)}`, { params: { withTodos } })
}

/* ---------- 素材库 ---------- */

/** GET /api/materials —— 素材列表（内置 + 云端上传） */
export async function fetchMaterials(): Promise<Material[]> {
  const { data } = await http.get<Material[]>('/api/materials')
  return data
}

/** GET /api/materials/capabilities —— 上传能力与限制（只读） */
export async function fetchMaterialCapabilities(): Promise<MaterialCapabilities> {
  const { data } = await http.get<MaterialCapabilities>('/api/materials/capabilities')
  return data
}

/** GET /api/materials/{id} —— 单个素材（转码轮询用） */
export async function fetchMaterial(id: string): Promise<Material> {
  const { data } = await http.get<Material>(`/api/materials/${encodeURIComponent(id)}`)
  return data
}

/**
 * POST /api/materials —— 上传视频到保利威点播。
 *
 * <p>用 XMLHttpRequest 而不是 axios/fetch：只有 XHR 能拿到真实的
 * `upload.onprogress`，fetch 无法给出上传进度。
 */
export function uploadMaterial(
  file: File,
  onProgress?: (percent: number) => void,
): Promise<Material> {
  return new Promise((resolve, reject) => {
    const form = new FormData()
    form.append('file', file)
    const xhr = new XMLHttpRequest()
    xhr.open('POST', '/api/materials')
    xhr.responseType = 'text'
    xhr.timeout = 180000
    if (xhr.upload && onProgress) {
      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable && event.total > 0) {
          onProgress(Math.min(100, Math.round((event.loaded / event.total) * 100)))
        }
      }
    }
    xhr.onload = () => {
      let body: unknown = null
      try {
        body = xhr.responseText ? JSON.parse(xhr.responseText) : null
      } catch {
        body = null
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(body as Material)
        return
      }
      const detail = (body as { error?: { code?: string; message?: string } } | null)?.error
      reject(new ApiError(detail?.code ?? `HTTP_${xhr.status}`, detail?.message ?? `上传失败（HTTP ${xhr.status}）`, xhr.status))
    }
    xhr.onerror = () => reject(new ApiError('NETWORK_ERROR', '上传失败：无法连接后端服务', null))
    xhr.ontimeout = () => reject(new ApiError('UPLOAD_TIMEOUT', '上传超时，请检查网络后重试', null))
    xhr.send(form)
  })
}

/** GET /api/materials/{id}/play —— 取保利威 CDN 播放地址 */
export async function fetchMaterialPlay(id: string): Promise<MaterialPlay> {
  const { data } = await http.get<MaterialPlay>(`/api/materials/${encodeURIComponent(id)}/play`)
  return data
}

/** DELETE /api/materials/{id} —— 删除素材（内置素材被后端拒绝） */
export async function deleteMaterial(id: string): Promise<void> {
  await http.delete(`/api/materials/${encodeURIComponent(id)}`)
}

/** POST /api/materials/{id}/subtitle —— 上传本地 SRT 字幕 */
export async function uploadMaterialSubtitle(id: string, file: File): Promise<Material> {
  const form = new FormData()
  form.append('file', file)
  const { data } = await http.post<Material>(
    `/api/materials/${encodeURIComponent(id)}/subtitle`,
    form,
    { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 60000 },
  )
  return data
}

/** DELETE /api/materials/{id}/subtitle —— 移除已上传的本地 SRT */
export async function deleteMaterialSubtitle(id: string): Promise<Material> {
  const { data } = await http.delete<Material>(`/api/materials/${encodeURIComponent(id)}/subtitle`)
  return data
}

/* ---------- 核验事项 CSV 批量导入 ---------- */

/** GET /api/cases/import/template —— 下载 CSV 模板 */
export async function downloadCaseImportTemplate(): Promise<Blob> {
  const { data } = await http.get<Blob>('/api/cases/import/template', { responseType: 'blob' })
  return data
}

/** POST /api/cases/import/preview —— 预览解析结果（绝不写库） */
export async function previewCaseImport(
  materialId: string,
  file: File,
): Promise<CaseImportPreview> {
  const form = new FormData()
  form.append('file', file)
  const { data } = await http.post<CaseImportPreview>('/api/cases/import/preview', form, {
    params: { materialId },
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 60000,
  })
  return data
}

/** POST /api/cases/import —— 确认导入（strict 下有任何非法行则整批拒绝） */
export async function importCases(
  materialId: string,
  file: File,
  policy: CaseImportPolicy = 'strict',
): Promise<CaseImportResult> {
  const form = new FormData()
  form.append('file', file)
  const { data } = await http.post<CaseImportResult>('/api/cases/import', form, {
    params: { materialId, policy },
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 60000,
  })
  return data
}

/** GET /api/cases/{id} —— 用例详情（含 reasoning / limitations / frames） */
export async function fetchCase(id: string): Promise<CaseDetail> {
  const { data } = await http.get<CaseDetail>(`/api/cases/${encodeURIComponent(id)}`)
  return data
}

/** PATCH /api/cases/{id} —— 只提交变化的字段，四（六）字段互不推导 */
export async function patchCase(id: string, payload: CasePatchPayload): Promise<CaseDetail> {
  const { data } = await http.patch<CaseDetail>(`/api/cases/${encodeURIComponent(id)}`, payload)
  return data
}

/** GET /api/subtitles?video=... —— 本地 SRT 解析出的 cue 列表 */
export async function fetchSubtitles(video: string): Promise<SubtitleResponse> {
  const { data } = await http.get<SubtitleResponse>('/api/subtitles', { params: { video } })
  return data
}

/** GET /api/smart-subtitles/status —— 云端智能字幕接入状态（只读，不触发云端调用） */
export async function fetchSmartSubtitleStatus(): Promise<SmartSubtitleStatusInfo> {
  const { data } = await http.get<SmartSubtitleStatusInfo>('/api/smart-subtitles/status')
  return data
}

/** GET /api/smart-subtitles/tasks —— 本地登记的任务（不查云端） */
export async function fetchSmartSubtitleTasks(video?: string): Promise<SmartSubtitleTask[]> {
  const { data } = await http.get<SmartSubtitleTask[]>('/api/smart-subtitles/tasks', {
    params: video ? { video } : undefined,
  })
  return data
}

/**
 * POST /api/smart-subtitles/tasks —— 创建或复用任务。
 * 复用既有任务时后端返回 200（reused=true），不会重复消耗资源点。
 */
export async function createSmartSubtitleTask(video: string): Promise<SmartSubtitleTask> {
  const { data } = await http.post<SmartSubtitleTask>(
    '/api/smart-subtitles/tasks',
    { video },
    { timeout: 30000 },
  )
  return data
}

/** GET /api/smart-subtitles/tasks/{taskId} —— 真实复查云端一次 */
export async function refreshSmartSubtitleTask(taskId: string): Promise<SmartSubtitleTask> {
  const { data } = await http.get<SmartSubtitleTask>(
    `/api/smart-subtitles/tasks/${encodeURIComponent(taskId)}`,
    { timeout: 30000 },
  )
  return data
}

/** GET /api/smart-subtitles/tasks/{taskId}/cues —— 取回规范化字幕（毫秒已转秒） */
export async function fetchSmartSubtitleCues(
  taskId: string,
  window: { from?: number | null; to?: number | null } = {},
): Promise<SmartSubtitleCues> {
  const { data } = await http.get<SmartSubtitleCues>(
    `/api/smart-subtitles/tasks/${encodeURIComponent(taskId)}/cues`,
    {
      params: {
        from: window.from ?? undefined,
        to: window.to ?? undefined,
      },
      timeout: 30000,
    },
  )
  return data
}

/** GET /api/todos —— 待办列表（创建时间倒序） */
export async function fetchTodos(params: { status?: TodoStatus; caseId?: string } = {}): Promise<Todo[]> {
  const { data } = await http.get<Todo[]>('/api/todos', { params })
  return data
}

/** GET /api/todos/{id} */
export async function fetchTodo(id: number): Promise<Todo> {
  const { data } = await http.get<Todo>(`/api/todos/${id}`)
  return data
}

/** POST /api/todos —— 201，状态 open、来源 manual */
export async function createTodo(payload: TodoCreatePayload): Promise<Todo> {
  const { data } = await http.post<Todo>('/api/todos', payload)
  return data
}

/** PATCH /api/todos/{id} */
export async function updateTodo(id: number, payload: TodoUpdatePayload): Promise<Todo> {
  const { data } = await http.patch<Todo>(`/api/todos/${id}`, payload)
  return data
}

/** POST /api/todos/{id}/complete —— 幂等 */
export async function completeTodo(id: number): Promise<Todo> {
  const { data } = await http.post<Todo>(`/api/todos/${id}/complete`)
  return data
}

/** GET /api/ai/status —— AI 能力状态（不触发模型调用，用于展示"未配置"） */
export async function fetchAiStatus(): Promise<AiStatus> {
  const { data } = await http.get<AiStatus>('/api/ai/status')
  return data
}

/**
 * POST /api/cases/{id}/ai-review —— 对单条用例发起一次真实模型分析。
 * 只在用户点击时调用；返回的是建议，不写库，也不含 reviewStatus。
 *
 * @param subtitleSource 证据定位用的字幕来源，缺省 local（与接入云端前一致）
 */
export async function requestAiReview(
  caseId: string,
  subtitleSource: SubtitleSource = 'local',
): Promise<AiReviewResponse> {
  const { data } = await http.post<AiReviewResponse>(
    `/api/cases/${encodeURIComponent(caseId)}/ai-review`,
    undefined,
    {
      params: { subtitleSource },
      // 模型调用耗时远高于普通接口，单独放宽超时
      timeout: 120000,
    },
  )
  return data
}

/**
 * POST /api/reports/review —— 按数据集即时生成复盘报告（Markdown）。
 *
 * @param materialId 可选：只复盘某个素材下的核验事项与待办
 */
export async function generateReviewReport(ds = 'cf', materialId?: string | null): Promise<ReviewReport> {
  const { data } = await http.post<ReviewReport>('/api/reports/review', undefined, {
    params: { ds, materialId: materialId || undefined },
    timeout: 120000,
  })
  return data
}
