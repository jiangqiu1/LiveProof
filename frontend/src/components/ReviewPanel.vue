<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type {
  AiAdoptPayload,
  AnswerStatus,
  CaseDetail,
  CasePatchPayload,
  EvidenceSufficiency,
  EvidenceType,
  ReviewStatus,
} from '@/types/api'
import {
  ANSWER_STATUS_LABEL,
  ANSWER_STATUS_OPTIONS,
  EVIDENCE_SUFFICIENCY_LABEL,
  EVIDENCE_SUFFICIENCY_OPTIONS,
  EVIDENCE_TYPE_LABEL,
  EVIDENCE_TYPE_OPTIONS,
  REVIEW_STATUS_LABEL,
  REVIEW_STATUS_OPTIONS,
  formatDateTime,
  formatRange,
  formatTime,
} from '@/constants/meta'
import SegmentedField from '@/components/SegmentedField.vue'
import AiReviewPanel from '@/components/AiReviewPanel.vue'
import { usePlayerStore } from '@/stores/player'

const props = defineProps<{
  detail: CaseDetail | null
  saving: boolean
  loading: boolean
  error: string | null
  lastSavedAt: string | null
}>()

const emit = defineEmits<{
  (e: 'save', payload: CasePatchPayload): void
  (e: 'create-todo'): void
  (e: 'seek', sec: number): void
  (e: 'dirty', value: boolean): void
}>()

const player = usePlayerStore()

const reviewStatus = ref<ReviewStatus>('unreviewed')
const answerStatus = ref<AnswerStatus>('not_found')
const evidenceType = ref<EvidenceType>('unknown')
const evidenceSufficiency = ref<EvidenceSufficiency>('not_checked')
const reasoning = ref('')
const limitations = ref('')
/** 人工标记的关联时间点（秒）：detail.timePoints 是已保存值，本地草稿在保存前可增删 */
const markedPoints = ref<number[]>([])
const pointHint = ref('')

const TEXT_LIMIT = 1000
const MAX_TIME_POINTS = 20

watch(
  () => props.detail,
  (detail) => {
    if (!detail) return
    reviewStatus.value = detail.reviewStatus
    answerStatus.value = detail.answerStatus
    evidenceType.value = detail.evidenceType
    evidenceSufficiency.value = detail.evidenceSufficiency
    reasoning.value = detail.reasoning ?? ''
    limitations.value = detail.limitations ?? ''
    markedPoints.value = [...(detail.timePoints ?? [])]
    pointHint.value = ''
  },
  { immediate: true },
)

/** 时间点比较：统一按两位小数判等，避免后端 12.5 与本地 12.50 被当成不同值 */
function samePoints(a: number[], b: number[]): boolean {
  if (a.length !== b.length) return false
  return a.every((value, index) => Math.abs(value - b[index]) < 0.005)
}

const savedPoints = computed(() => props.detail?.timePoints ?? [])
const pointsDirty = computed(() => !samePoints(markedPoints.value, savedPoints.value))

const payload = computed<CasePatchPayload>(() => {
  const detail = props.detail
  const result: CasePatchPayload = {}
  if (!detail) return result
  if (reviewStatus.value !== detail.reviewStatus) result.reviewStatus = reviewStatus.value
  if (answerStatus.value !== detail.answerStatus) result.answerStatus = answerStatus.value
  if (evidenceType.value !== detail.evidenceType) result.evidenceType = evidenceType.value
  if (evidenceSufficiency.value !== detail.evidenceSufficiency) {
    result.evidenceSufficiency = evidenceSufficiency.value
  }
  if (reasoning.value !== (detail.reasoning ?? '')) result.reasoning = reasoning.value
  if (limitations.value !== (detail.limitations ?? '')) result.limitations = limitations.value
  if (pointsDirty.value) result.timePoints = [...markedPoints.value]
  return result
})

const changedFields = computed(() => Object.keys(payload.value))
const hasChanges = computed(() => changedFields.value.length > 0)

const timePoints = computed(() => {
  const detail = props.detail
  const points: { key: string; label: string; sec: number; removable: boolean }[] = []
  if (detail?.startSec !== null && detail?.startSec !== undefined) {
    points.push({ key: 'start', label: `起点 ${formatTime(detail.startSec)}`, sec: detail.startSec, removable: false })
  }
  if (detail?.endSec !== null && detail?.endSec !== undefined) {
    points.push({ key: 'end', label: `终点 ${formatTime(detail.endSec)}`, sec: detail.endSec, removable: false })
  }
  markedPoints.value.forEach((sec, index) => {
    points.push({ key: `m${index}`, label: formatTime(sec), sec, removable: true })
  })
  return points
})

function addTimePoint() {
  const sec = Number(player.currentTime.toFixed(2))
  if (markedPoints.value.some((item) => Math.abs(item - sec) < 0.5)) {
    pointHint.value = `${formatTime(sec)} 已有标记，未重复添加`
    return
  }
  if (markedPoints.value.length >= MAX_TIME_POINTS) {
    pointHint.value = `最多标记 ${MAX_TIME_POINTS} 个时间点，请先移除不需要的标记`
    return
  }
  markedPoints.value = [...markedPoints.value, sec].sort((a, b) => a - b)
  pointHint.value = ''
}

function removeTimePoint(sec: number) {
  markedPoints.value = markedPoints.value.filter((item) => item !== sec)
  pointHint.value = ''
}

defineExpose({ submit })

function submit() {
  if (!props.detail || !hasChanges.value || props.saving) return
  emit('save', payload.value)
}

function setReview(value: string) {
  reviewStatus.value = value as ReviewStatus
}
function setAnswer(value: string) {
  answerStatus.value = value as AnswerStatus
}
function setEvidenceType(value: string) {
  evidenceType.value = value as EvidenceType
}
function setSufficiency(value: string) {
  evidenceSufficiency.value = value as EvidenceSufficiency
}

/**
 * 采纳 AI 建议：只写入本组件的编辑态，不落库、不改 reviewStatus。
 * 用户仍需点「保存核验结果」才会调用 PATCH。
 */
function applyAi(payload: AiAdoptPayload) {
  if (payload.answerStatus) answerStatus.value = payload.answerStatus
  if (payload.evidenceType) evidenceType.value = payload.evidenceType
  if (payload.evidenceSufficiency) evidenceSufficiency.value = payload.evidenceSufficiency
  if (payload.reasoning !== undefined) reasoning.value = payload.reasoning
  if (payload.limitations !== undefined) limitations.value = payload.limitations
  if (payload.timePoints && payload.timePoints.length > 0) {
    const merged = [...markedPoints.value]
    for (const sec of payload.timePoints) {
      if (merged.length >= MAX_TIME_POINTS) break
      const value = Number(sec.toFixed(2))
      if (!merged.some((item) => Math.abs(item - value) < 0.5)) merged.push(value)
    }
    markedPoints.value = merged.sort((a, b) => a - b)
  }
  pointHint.value = '已采纳 AI 建议，请核对后保存'
}

watch(hasChanges, (value) => emit('dirty', value), { immediate: true })
</script>

<template>
  <section class="lp-card" style="min-height: 0; overflow: hidden">
    <div class="lp-card-head">
      <div class="lp-card-title">核验结论</div>
      <span v-if="hasChanges" class="lp-dirty">{{ changedFields.length }} 项未保存</span>
    </div>

    <div v-if="error" class="lp-note warn" style="margin: 10px 12px">{{ error }}</div>

    <div v-if="loading" class="lp-empty">正在加载用例详情…</div>
    <div v-else-if="!detail" class="lp-empty">从左侧选择一个待核验问题</div>

    <div v-else class="lp-review">
      <div>
        <div class="lp-review-question">{{ detail.question }}</div>
        <div class="lp-review-meta">
          <svg
            width="13"
            height="13"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
          >
            <circle cx="12" cy="12" r="9" />
            <path d="M12 7.5V12l3 2" />
          </svg>
          演示时间 {{ formatRange(detail.startSec, detail.endSec) }}
        </div>
        <div v-if="detail.claim" style="margin-top: 6px; font-size: 12px; color: var(--lp-muted)">
          {{ detail.claim }}
        </div>
      </div>

      <AiReviewPanel :detail="detail" :saving="saving" @seek="emit('seek', $event)" @apply="applyAi" />

      <div class="lp-divider"></div>

      <div class="lp-field">
        <div class="lp-field-label">
          <span>核验进度</span>
          <span class="hint">{{ REVIEW_STATUS_LABEL[reviewStatus] }}</span>
        </div>
        <SegmentedField
          :model-value="reviewStatus"
          :options="REVIEW_STATUS_OPTIONS"
          :disabled="saving"
          @update:model-value="setReview"
        />
      </div>

      <div class="lp-field">
        <div class="lp-field-label">
          <span>回答状态</span>
          <span class="hint">{{ ANSWER_STATUS_LABEL[answerStatus] }}</span>
        </div>
        <SegmentedField
          :model-value="answerStatus"
          :options="ANSWER_STATUS_OPTIONS"
          :disabled="saving"
          @update:model-value="setAnswer"
        />
      </div>

      <div class="lp-field">
        <div class="lp-field-label">
          <span>证据类型</span>
          <span class="hint">{{ EVIDENCE_TYPE_LABEL[evidenceType] }}</span>
        </div>
        <SegmentedField
          :model-value="evidenceType"
          :options="EVIDENCE_TYPE_OPTIONS"
          :disabled="saving"
          @update:model-value="setEvidenceType"
        />
      </div>

      <div class="lp-field">
        <div class="lp-field-label">
          <span>证据充分性</span>
          <span class="hint">{{ EVIDENCE_SUFFICIENCY_LABEL[evidenceSufficiency] }}</span>
        </div>
        <SegmentedField
          :model-value="evidenceSufficiency"
          :options="EVIDENCE_SUFFICIENCY_OPTIONS"
          :disabled="saving"
          :warn-values="['insufficient']"
          @update:model-value="setSufficiency"
        />
      </div>

      <div class="lp-field">
        <div class="lp-field-label">
          <span>核验依据</span>
        </div>
        <el-input
          v-model="reasoning"
          type="textarea"
          :rows="3"
          :maxlength="TEXT_LIMIT"
          :disabled="saving"
          placeholder="填入核验依据，例如：01:05 处出现导入结果表格，演示过程完整。"
        />
        <div class="lp-counter">{{ reasoning.length }} / {{ TEXT_LIMIT }}</div>
      </div>

      <div class="lp-field">
        <div class="lp-field-label">
          <span>局限与风险</span>
        </div>
        <el-input
          v-model="limitations"
          type="textarea"
          :rows="3"
          :maxlength="TEXT_LIMIT"
          :disabled="saving"
          placeholder="填入本次核验的局限或风险，例如：未展示 5000 条规模，性能无法确认。"
        />
        <div class="lp-counter">{{ limitations.length }} / {{ TEXT_LIMIT }}</div>
      </div>

      <div class="lp-field">
        <div class="lp-field-label">
          <span>关联时间点</span>
          <button type="button" class="lp-btn lp-btn-text" style="height: 22px" @click="addTimePoint">
            + 标记当前进度
          </button>
        </div>
        <div class="lp-chip-row">
          <button
            v-for="point in timePoints"
            :key="point.key"
            type="button"
            class="lp-chip"
            :class="{ pending: point.removable && pointsDirty }"
            @click="emit('seek', point.sec)"
          >
            {{ point.label }}
            <span
              v-if="point.removable"
              class="x"
              title="移除该标记（保存后生效）"
              @click.stop="removeTimePoint(point.sec)"
              >×</span
            >
          </button>
        </div>
        <div v-if="pointHint" style="font-size: 11px; color: var(--lp-warn-text)">{{ pointHint }}</div>
        <div style="font-size: 11px; color: var(--lp-muted-soft)">
          起点 / 终点来自用例记录，只读；标记点随「保存核验结果」写入后端（0–7200 秒，最多
          {{ MAX_TIME_POINTS }} 个）。
        </div>
      </div>

      <div class="lp-note">
        四个字段相互独立：既有取值组合都<b>不等于「内容为真」</b>，默认状态需人工改判。
      </div>

      <div class="lp-foot-actions">
        <button type="button" class="lp-btn lp-btn-primary" :disabled="!hasChanges || saving" @click="submit">
          {{ saving ? '保存中…' : '保存核验结果' }}
        </button>
        <button type="button" class="lp-btn" :disabled="saving" @click="emit('create-todo')">创建后续待办</button>
      </div>

      <div style="font-size: 11px; color: var(--lp-muted-soft); text-align: right">
        最近保存 {{ formatDateTime(lastSavedAt ?? detail.updatedAt) }}
      </div>
    </div>
  </section>
</template>
