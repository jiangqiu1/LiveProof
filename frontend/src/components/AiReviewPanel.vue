<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type {
  AiAdoptPayload,
  AiEvidenceItem,
  AiFrameRef,
  AiReviewResponse,
  AiStatus,
  CaseDetail,
} from '@/types/api'
import { fetchAiStatus, requestAiReview } from '@/api'
import { ApiError, describeError } from '@/api/client'
import { useWorkbenchStore } from '@/stores/workbench'
import {
  ANSWER_STATUS_LABEL,
  EVIDENCE_SUFFICIENCY_LABEL,
  EVIDENCE_TYPE_LABEL,
  formatRange,
  formatTime,
} from '@/constants/meta'

const props = defineProps<{
  detail: CaseDetail | null
  /** 保存中时禁用采纳，避免和 PATCH 并发 */
  saving: boolean
}>()

const emit = defineEmits<{
  (e: 'seek', sec: number): void
  (e: 'apply', payload: AiAdoptPayload): void
}>()

const status = ref<AiStatus | null>(null)
const statusLoading = ref(false)
const statusError = ref<string | null>(null)

/** AI 核验按工作台当前字幕来源定位证据，来源不同结论可能不同，界面上必须写清 */
const store = useWorkbenchStore()
const subtitleSourceLabel = computed(() =>
  store.subtitleSource === 'cloud' ? '保利威智能字幕' : '本地 SRT 字幕',
)

const analyzing = ref(false)
const error = ref<{ code: string; message: string } | null>(null)
const result = ref<AiReviewResponse | null>(null)

const adopt = ref({
  answerStatus: true,
  evidenceType: true,
  evidenceSufficiency: true,
  reasoning: true,
  limitations: true,
  timePoints: true,
})

const suggestion = computed(() => result.value?.suggestion ?? null)
const context = computed(() => result.value?.context ?? null)

const statusLabel = computed(() => {
  if (statusError.value) return '状态未知'
  if (!status.value) return '检测中'
  if (!status.value.configured) return '未配置'
  return status.value.ffmpegAvailable ? '字幕 + 画面' : '仅字幕'
})

const statusTone = computed(() => {
  if (!status.value?.configured || statusError.value) return 'warn'
  return 'mint'
})

const canAnalyze = computed(
  () => Boolean(props.detail) && Boolean(status.value?.configured) && !analyzing.value && !props.saving,
)

const buttonLabel = computed(() => {
  if (analyzing.value) return '分析中…'
  if (!status.value) return statusLoading.value ? '检测 AI 服务…' : 'AI 辅助核验'
  if (!status.value.configured) return 'AI 未配置'
  return result.value ? '重新分析' : 'AI 辅助核验'
})

const frameLookup = computed(() => {
  const map = new Map<number, AiFrameRef>()
  ;(context.value?.frames ?? []).forEach((frame) => map.set(Number(frame.sec.toFixed(2)), frame))
  return map
})

const selectedCount = computed(() => {
  if (!suggestion.value) return 0
  let count = 0
  if (adopt.value.answerStatus) count++
  if (adopt.value.evidenceType) count++
  if (adopt.value.evidenceSufficiency) count++
  if (adopt.value.reasoning) count++
  if (adopt.value.limitations) count++
  if (adopt.value.timePoints && suggestion.value.suggestedTimePoints.length > 0) count++
  return count
})

const notConfiguredHint = computed(() => {
  if (!status.value || status.value.configured) return ''
  return '需要在后端环境变量中设置 DEEPSEEK_API_KEY（可同时配置 DEEPSEEK_BASE_URL / DEEPSEEK_MODEL）后重启服务。未配置时不会生成任何模拟分析。'
})

async function loadStatus() {
  statusLoading.value = true
  statusError.value = null
  try {
    status.value = await fetchAiStatus()
  } catch (err) {
    status.value = null
    statusError.value = describeError(err)
  } finally {
    statusLoading.value = false
  }
}

function resetResult() {
  result.value = null
  error.value = null
  adopt.value = {
    answerStatus: true,
    evidenceType: true,
    evidenceSufficiency: true,
    reasoning: true,
    limitations: true,
    timePoints: true,
  }
}

async function analyze() {
  const detail = props.detail
  if (!detail || analyzing.value) return
  if (!status.value?.configured) {
    error.value = {
      code: 'AI_NOT_CONFIGURED',
      message: 'AI 服务未配置，无法发起分析。',
    }
    return
  }
  analyzing.value = true
  error.value = null
  try {
    const data = await requestAiReview(detail.id, store.subtitleSource)
    result.value = data
    // 已有文字内容时默认不勾选，避免误覆盖人工已写过的依据
    adopt.value = {
      answerStatus: true,
      evidenceType: true,
      evidenceSufficiency: true,
      reasoning: !detail.reasoning,
      limitations: !detail.limitations,
      timePoints: true,
    }
  } catch (err) {
    result.value = null
    const code = err instanceof ApiError ? err.code : 'UNKNOWN'
    error.value = { code, message: describeError(err) }
    if (code === 'AI_NOT_CONFIGURED') {
      await loadStatus()
    }
  } finally {
    analyzing.value = false
  }
}

function applySelection() {
  const data = suggestion.value
  if (!data || selectedCount.value === 0) return
  const payload: AiAdoptPayload = {}
  if (adopt.value.answerStatus) payload.answerStatus = data.answerStatus
  if (adopt.value.evidenceType) payload.evidenceType = data.evidenceType
  if (adopt.value.evidenceSufficiency) payload.evidenceSufficiency = data.evidenceSufficiency
  if (adopt.value.reasoning) payload.reasoning = buildReasoning(data.reasoning, data.summary)
  if (adopt.value.limitations) payload.limitations = data.limitations
  if (adopt.value.timePoints && data.suggestedTimePoints.length > 0) {
    payload.timePoints = [...data.suggestedTimePoints]
  }
  emit('apply', payload)
}

/** 核验依据写入时带一句来源说明，避免以后无法区分"人写的"和"AI 建议后采纳的" */
function buildReasoning(reasoning: string, summary: string): string {
  const text = reasoning?.trim() ? reasoning.trim() : summary
  const prefix = '（AI 辅助建议，已人工采纳待复核）'
  const merged = `${prefix}${text}`
  return merged.length > 1000 ? merged.slice(0, 1000) : merged
}

function frameOf(item: AiEvidenceItem): AiFrameRef | null {
  if (item.frameSec === null || item.frameSec === undefined) return null
  const key = Number(Number(item.frameSec).toFixed(2))
  return frameLookup.value.get(key) ?? null
}

function outsideLabel(item: AiEvidenceItem): string {
  return item.outsideWindow ? '原时间区间之外' : ''
}

watch(
  () => props.detail?.id,
  () => {
    resetResult()
  },
  { immediate: true },
)

// 换字幕来源后旧建议不再对应当前输入，直接作废，避免把 A 来源的结论套到 B 来源上
watch(
  () => store.subtitleSource,
  () => {
    resetResult()
  },
)

onMounted(loadStatus)
</script>

<template>
  <div class="lp-ai">
    <div class="lp-ai-head">
      <div class="lp-ai-title">
        <span class="lp-ai-mark">AI</span>
        <span>辅助核验</span>
        <span class="lp-pill" :class="statusTone">{{ statusLabel }}</span>
      </div>
      <button
        type="button"
        class="lp-btn lp-btn-primary"
        style="height: 26px; padding: 0 10px; font-size: 12px"
        :disabled="!canAnalyze"
        :title="status?.configured ? '对当前事项发起一次模型分析' : 'AI 服务未配置'"
        @click="analyze"
      >
        {{ buttonLabel }}
      </button>
    </div>

    <div class="lp-ai-sub">
      AI 只提出建议：不会修改「核验进度」，也不会自动保存。采纳后仍需点击「保存核验结果」。
      本次定位使用：{{ subtitleSourceLabel }}（在工作台字幕面板可切换）。
    </div>

    <div v-if="statusError" class="lp-note warn">无法读取 AI 能力状态：{{ statusError }}</div>

    <div v-else-if="status && !status.configured" class="lp-note warn">
      {{ notConfiguredHint }}
      <div style="margin-top: 4px">
        模型：{{ status.model }} · 服务：{{ status.endpointHost }} ·
        FFmpeg：{{ status.ffmpegAvailable ? '可用' : '不可用（只能基于字幕判断）' }}
      </div>
    </div>

    <div v-else-if="status" class="lp-ai-meta">
      模型 {{ status.model }} · 服务 {{ status.endpointHost }} · 关键帧上限
      {{ status.maxFrames }} 帧 · FFmpeg {{ status.ffmpegAvailable ? '可用' : '不可用' }}
    </div>

    <div v-if="analyzing" class="lp-ai-loading">
      <span class="lp-ai-spinner"></span>
      正在分析字幕与关键帧，通常在 10–40 秒内返回…
    </div>

    <div v-if="error" class="lp-note warn">
      <div>{{ error.message }}</div>
      <div v-if="error.code === 'AI_NOT_CONFIGURED'" style="margin-top: 4px; color: var(--lp-muted)">
        这是部署状态问题：配置 DEEPSEEK_API_KEY 后重启后端即可。
      </div>
      <div v-if="error.code === 'SMART_SUBTITLE_NOT_READY'" style="margin-top: 4px; color: var(--lp-muted)">
        云端字幕还没有就绪任务：可先在「素材与字幕」页创建或查询，或在字幕面板切回本地字幕。
      </div>
      <div style="margin-top: 6px; display: flex; gap: 8px">
        <button type="button" class="lp-btn" style="height: 24px" :disabled="analyzing" @click="analyze">
          重试
        </button>
        <button type="button" class="lp-btn" style="height: 24px" @click="loadStatus">重新检测状态</button>
      </div>
    </div>

    <div v-if="suggestion && context" class="lp-ai-result">
      <div class="lp-ai-summary">{{ suggestion.summary }}</div>

      <div class="lp-ai-adopt">
        <label class="lp-ai-row">
          <el-checkbox v-model="adopt.answerStatus" size="small" />
          <span class="k">回答状态</span>
          <span class="lp-pill ink">{{ ANSWER_STATUS_LABEL[suggestion.answerStatus] }}</span>
          <span v-if="detail && detail.answerStatus !== suggestion.answerStatus" class="old">
            当前 {{ ANSWER_STATUS_LABEL[detail.answerStatus] }}
          </span>
        </label>
        <label class="lp-ai-row">
          <el-checkbox v-model="adopt.evidenceType" size="small" />
          <span class="k">证据形态</span>
          <span class="lp-pill ink">{{ EVIDENCE_TYPE_LABEL[suggestion.evidenceType] }}</span>
          <span v-if="detail && detail.evidenceType !== suggestion.evidenceType" class="old">
            当前 {{ EVIDENCE_TYPE_LABEL[detail.evidenceType] }}
          </span>
        </label>
        <label class="lp-ai-row">
          <el-checkbox v-model="adopt.evidenceSufficiency" size="small" />
          <span class="k">证据充分性</span>
          <span class="lp-pill" :class="suggestion.evidenceSufficiency === 'sufficient' ? 'mint' : 'warn'">
            {{ EVIDENCE_SUFFICIENCY_LABEL[suggestion.evidenceSufficiency] }}
          </span>
          <span v-if="detail && detail.evidenceSufficiency !== suggestion.evidenceSufficiency" class="old">
            当前 {{ EVIDENCE_SUFFICIENCY_LABEL[detail.evidenceSufficiency] }}
          </span>
        </label>
      </div>

      <div class="lp-ai-block">
        <div class="lp-ai-block-head">
          <label class="lp-ai-row" style="gap: 6px">
            <el-checkbox v-model="adopt.reasoning" size="small" />
            <span class="k">AI 判断依据</span>
          </label>
        </div>
        <div class="lp-ai-text">{{ suggestion.reasoning }}</div>
      </div>

      <div class="lp-ai-block">
        <div class="lp-ai-block-head">
          <label class="lp-ai-row" style="gap: 6px">
            <el-checkbox v-model="adopt.limitations" size="small" />
            <span class="k">局限与风险</span>
          </label>
        </div>
        <div class="lp-ai-text">{{ suggestion.limitations }}</div>
      </div>

      <div v-if="suggestion.evidenceItems.length" class="lp-ai-block">
        <div class="lp-ai-block-head"><span class="k">候选证据（点击时间跳转）</span></div>
        <div v-for="(item, index) in suggestion.evidenceItems" :key="index" class="lp-ai-evidence">
          <div class="lp-ai-evidence-head">
            <button type="button" class="lp-chip" @click="emit('seek', item.startSec)">
              {{ formatTime(item.startSec) }} - {{ formatTime(item.endSec) }}
            </button>
            <span v-if="outsideLabel(item)" class="lp-pill warn">{{ outsideLabel(item) }}</span>
          </div>
          <div v-if="item.subtitle" class="lp-ai-quote">字幕：{{ item.subtitle }}</div>
          <div v-if="item.observation" class="lp-ai-quote">画面：{{ item.observation }}</div>
          <div class="lp-ai-evidence-line"><b>能支持</b>{{ item.supports }}</div>
          <div class="lp-ai-evidence-line"><b>不能证明</b>{{ item.cannotProve }}</div>
          <figure v-if="frameOf(item)" class="lp-ai-frame">
            <img :src="frameOf(item)!.dataUrl ?? ''" :alt="`关键帧 ${formatTime(item.frameSec ?? 0)}`" />
            <figcaption>
              提交给模型的关键帧 {{ formatTime(item.frameSec ?? 0) }}
              <template v-if="frameOf(item)!.width">
                · {{ frameOf(item)!.width }}×{{ frameOf(item)!.height }}
              </template>
            </figcaption>
          </figure>
        </div>
      </div>

      <div v-if="suggestion.suggestedTimePoints.length" class="lp-ai-block">
        <div class="lp-ai-block-head">
          <label class="lp-ai-row" style="gap: 6px">
            <el-checkbox v-model="adopt.timePoints" size="small" />
            <span class="k">建议回看时间点</span>
          </label>
        </div>
        <div class="lp-chip-row">
          <button
            v-for="point in suggestion.suggestedTimePoints"
            :key="point"
            type="button"
            class="lp-chip"
            @click="emit('seek', point)"
          >
            {{ formatTime(point) }}
          </button>
        </div>
      </div>

      <div v-if="suggestion.unresolvedQuestions.length" class="lp-ai-block">
        <div class="lp-ai-block-head"><span class="k">仍需人工确认</span></div>
        <ul class="lp-ai-list">
          <li v-for="(question, index) in suggestion.unresolvedQuestions" :key="index">{{ question }}</li>
        </ul>
      </div>

      <div v-if="result?.normalizationNotes.length" class="lp-ai-notes">
        <div v-for="(note, index) in result.normalizationNotes" :key="index">· {{ note }}</div>
      </div>

      <div class="lp-ai-context">
        分析范围：字幕 {{ context.cuesUsed.length }}/{{ context.totalCueCount }} 条 ·
        来源 {{ context.subtitleSource === 'cloud' ? '保利威智能字幕' : '本地 SRT' }} ·
        {{ context.visionUsed ? `关键帧 ${context.frames.length} 帧` : '未使用画面' }} ·
        区间 {{ formatRange(detail?.startSec ?? null, detail?.endSec ?? null) }} ·
        耗时 {{ (context.elapsedMs / 1000).toFixed(1) }}s
        <template v-if="context.promptTokens">
          · tokens {{ context.promptTokens }}/{{ context.completionTokens }}
        </template>
        <div v-if="context.frameNote" class="muted">{{ context.frameNote }}</div>
      </div>

      <div class="lp-ai-actions">
        <button
          type="button"
          class="lp-btn lp-btn-primary"
          style="height: 26px"
          :disabled="selectedCount === 0 || saving"
          @click="applySelection"
        >
          采纳并应用（{{ selectedCount }} 项）
        </button>
        <button type="button" class="lp-btn" style="height: 26px" @click="resetResult">忽略建议</button>
      </div>
      <div class="lp-ai-foot">
        采纳只会写入左侧编辑态，需要再点击「保存核验结果」才会落库；核验进度不会改变。
      </div>
    </div>
  </div>
</template>
