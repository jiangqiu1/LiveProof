<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
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
import { CANNOT_PROVE_LABEL, SUPPORTS_LABEL, cannotProveText, supportsText } from '@/utils/aiEvidence'
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

/**
 * 折叠状态只影响展示，不参与请求与结果生命周期。
 *
 * <p>刻意放在本组件内部而不是提到 {@code ReviewPanel}：AI 的请求、结果与采纳逻辑都在这里，
 * 折叠只是它自己的一块 UI 状态，父组件不需要知道。折叠不清空 {@code result}、
 * 不触发 {@code analyze()}，重新展开不会重新请求。
 */
const expanded = ref(true)

const toggleExpanded = () => {
  expanded.value = !expanded.value
}

/**
 * 「AI 辅助核验」标题后问号所打开的说明浮层。
 *
 * <p>说明与模型信息原先直接铺在展开区顶部，实测占掉约 100px，一屏只剩摘要。
 * 收进浮层后内容一条不少，但不再挤占结果的可视高度。
 *
 * <p>用 Teleport + fixed 定位：{@code .lp-ai-fold} 自身是 {@code overflow: auto}，
 * 浮层若留在里面会被裁切，或把折叠区撑出一条多余的滚动条。
 */
const tipOpen = ref(false)
/**
 * 钉住状态：点击问号后浮层不随鼠标移开而关闭。
 *
 * <p>没有它会出现「鼠标移上去浮层已打开，点一下反而关了」——因为 hover 与 click
 * 都在操作同一个开关。钉住后两者语义分开：hover 是临时查看，click 是锁定查看。
 */
const tipPinned = ref(false)
const helpRef = ref<HTMLElement | null>(null)
const tipStyle = ref<Record<string, string>>({})
let tipCloseTimer: number | undefined

function placeTip() {
  const el = helpRef.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  const margin = 8
  // 窄视口下 320 可能放不下，兜底收到视口内
  const width = Math.min(320, window.innerWidth - margin * 2)
  // 问号贴在右栏右缘，320 宽的浮层只能向左翻；用 min/max 保证两侧都不越界
  const left = Math.min(Math.max(margin, rect.left - 24), window.innerWidth - width - margin)
  // 箭头按问号中心在浮层内的相对位置摆，浮层向左翻时也能指向按钮
  const arrowX = Math.min(Math.max(12, rect.left + rect.width / 2 - left - 4.5), width - 21)
  tipStyle.value = {
    top: `${Math.round(rect.bottom + 6)}px`,
    left: `${Math.round(left)}px`,
    width: `${width}px`,
    '--lp-tip-arrow-x': `${Math.round(arrowX)}px`,
  }
}

function openTip() {
  window.clearTimeout(tipCloseTimer)
  placeTip()
  tipOpen.value = true
}

/**
 * 延迟关闭：指针从问号移向浮层时会先触发按钮的 mouseleave，
 * 若立刻关闭就没有机会把指针移到浮层上，故留 160ms 跨过中间那 6px 间隙。
 */
function scheduleCloseTip() {
  if (tipPinned.value) return
  window.clearTimeout(tipCloseTimer)
  tipCloseTimer = window.setTimeout(() => {
    tipOpen.value = false
  }, 160)
}

function closeTip() {
  window.clearTimeout(tipCloseTimer)
  tipPinned.value = false
  tipOpen.value = false
}

function toggleTip() {
  if (tipPinned.value) {
    closeTip()
    return
  }
  tipPinned.value = true
  openTip()
}

function onDocumentPointerDown(event: MouseEvent) {
  const target = event.target as HTMLElement | null
  if (!target) return
  if (helpRef.value?.contains(target)) return
  if (target.closest?.('.lp-ai-tip')) return
  closeTip()
}

function onDocumentKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') closeTip()
}

/** 浮层是 fixed 定位，页面一滚或窗口一改尺寸就与问号脱位，直接关掉比让它飘着更清楚 */
function onDocumentScroll() {
  if (tipOpen.value) closeTip()
}

onMounted(() => {
  loadStatus()
  document.addEventListener('pointerdown', onDocumentPointerDown)
  document.addEventListener('keydown', onDocumentKeydown)
  window.addEventListener('scroll', onDocumentScroll, true)
  window.addEventListener('resize', onDocumentScroll, true)
})

onBeforeUnmount(() => {
  window.clearTimeout(tipCloseTimer)
  document.removeEventListener('pointerdown', onDocumentPointerDown)
  document.removeEventListener('keydown', onDocumentKeydown)
  window.removeEventListener('scroll', onDocumentScroll, true)
  window.removeEventListener('resize', onDocumentScroll, true)
})

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

/**
 * 「不能证明」那行的正文。
 *
 * <p>模板里已经写死了标签「不能证明」，而模型经常把这句话本身也写进 {@code cannotProve}
 * （实测 5 条里有 4 条以「不能证明」开头），拼起来就成了「不能证明不能证明……」。
 * 去重规则放在 {@code @/utils/aiEvidence}，那里能被验证脚本直接调用。
 */

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
</script>

<template>
  <div class="lp-ai" :class="{ 'is-collapsed': !expanded }">
    <div class="lp-ai-head">
      <div class="lp-ai-title">
        <button
          type="button"
          class="lp-ai-toggle"
          :aria-expanded="expanded"
          aria-controls="lp-ai-fold"
          :title="expanded ? '折叠 AI 建议' : '展开 AI 建议'"
          @click="toggleExpanded"
        >
          <svg
            class="lp-ai-chev"
            :class="{ 'is-open': expanded }"
            width="12"
            height="12"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2.2"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="M9 6l6 6-6 6" />
          </svg>
        </button>
        <span class="lp-ai-mark">AI</span>
        <span>辅助核验</span>
        <span class="lp-pill" :class="statusTone">{{ statusLabel }}</span>
        <button
          ref="helpRef"
          type="button"
          class="lp-ai-help"
          aria-label="AI 辅助核验的说明"
          :aria-expanded="tipOpen"
          @mouseenter="openTip"
          @mouseleave="scheduleCloseTip"
          @focus="openTip"
          @blur="scheduleCloseTip"
          @click="toggleTip"
        >
          ?
        </button>
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

    <!--
      折叠只收起「解释性内容」，下面这几类任何时候都可见：
      状态读取失败、未配置说明、运行中、请求错误、已有结果的一行摘要。
      否则用户在折叠状态点「AI 辅助核验」，失败后看不到任何反馈。
    -->
    <div v-if="statusError" class="lp-note warn">无法读取 AI 能力状态：{{ statusError }}</div>

    <div v-else-if="status && !status.configured" class="lp-note warn">
      {{ notConfiguredHint }}
      <div style="margin-top: 4px">
        模型：{{ status.model }} · 服务：{{ status.endpointHost }} ·
        FFmpeg：{{ status.ffmpegAvailable ? '可用' : '不可用（只能基于字幕判断）' }}
      </div>
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

    <!-- 折叠后仍让用户知道「已经分析过、结果还在」 -->
    <div v-if="!expanded && suggestion" class="lp-ai-folded">
      已有分析结果（未展开，不会重新请求）
      <button type="button" class="lp-btn lp-btn-text" style="height: 22px" @click="toggleExpanded">
        展开查看
      </button>
    </div>

    <div v-if="expanded" id="lp-ai-fold" class="lp-ai-fold">
      <div v-if="suggestion && context" class="lp-ai-result">
        <div class="lp-ai-summary">{{ suggestion.summary }}</div>

        <div class="lp-ai-adopt">
          <label class="lp-ai-row">
            <el-checkbox v-model="adopt.answerStatus" size="small" />
            <span class="k">回答状态</span>
            <span class="lp-pill ink">{{ ANSWER_STATUS_LABEL[suggestion.answerStatus] }}</span>
            <span v-if="detail && detail.answerStatus !== suggestion.answerStatus" class="old">
              当前 {{ detail.answerStatus ? ANSWER_STATUS_LABEL[detail.answerStatus] : '尚未核验' }}
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
            <div v-if="supportsText(item.supports)" class="lp-ai-evidence-line">
              <b>{{ SUPPORTS_LABEL }}</b>{{ supportsText(item.supports) }}
            </div>
            <div v-if="cannotProveText(item.cannotProve)" class="lp-ai-evidence-line">
              <b>{{ CANNOT_PROVE_LABEL }}</b>{{ cannotProveText(item.cannotProve) }}
            </div>
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

    <!--
      说明浮层：由标题后的问号打开，hover 与点击都能触发。
      Teleport 到 body 是为了绕开 .lp-ai-fold 的 overflow: auto，坐标由 placeTip() 按按钮位置算。
    -->
    <Teleport to="body">
      <div
        v-if="tipOpen"
        class="lp-ai-tip"
        role="tooltip"
        :style="tipStyle"
        @mouseenter="openTip"
        @mouseleave="scheduleCloseTip"
      >
        <div class="lp-ai-sub">
          AI 只提出建议：不会修改「核验进度」，也不会自动保存。采纳后仍需点击「保存核验结果」。
        </div>
        <div class="lp-ai-sub">
          本次定位使用：{{ subtitleSourceLabel }}（在工作台字幕面板可切换）。
        </div>
        <div v-if="status && status.configured" class="lp-ai-meta">
          模型 {{ status.model }}<br />
          服务 {{ status.endpointHost }}<br />
          关键帧上限 {{ status.maxFrames }} 帧 · FFmpeg
          {{ status.ffmpegAvailable ? '可用' : '不可用（只能基于字幕判断）' }}
        </div>
        <div v-else-if="statusError" class="lp-ai-meta">AI 能力状态读取失败，暂时无法发起分析。</div>
      </div>
    </Teleport>
  </div>
</template>
