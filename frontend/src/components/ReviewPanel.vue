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
import LpSkeletonList from '@/components/LpSkeletonList.vue'
import LpState from '@/components/LpState.vue'
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
/**
 * 回答状态：{@code null} = 尚未核验（V8 起后端落 NULL）。
 *
 * <p>不能默认给 'not_found'：那会让新建事项一进右栏就显示「未找到回答」，
 * 用户根本没做过这个判断。
 */
const answerStatus = ref<AnswerStatus | null>(null)
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
    answerStatus.value = detail.answerStatus ?? null
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
  // 只有选过具体值才下发：null 表示「这次不动这个问题」，后端也把 null 当作「不改」，
  // 界面没有任何入口能把已经判定过的回答状态改回未核验
  if (answerStatus.value && answerStatus.value !== detail.answerStatus) {
    result.answerStatus = answerStatus.value
  }
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

    <LpSkeletonList v-if="loading" :rows="3" />
    <LpState
      v-else-if="!detail"
      compact
      title="从左侧选择一个待核验问题"
      description="选中后这里显示问题、承诺、时间范围与四字段判定入口。"
    />

    <!--
      右栏分三层，彼此不共用一条滚动轴：
        .lp-review-ai   AI 建议区（可折叠；展开且有结果时限高，内部独立滚动）
        .lp-review-main 人工核验区（唯一的主滚动区）
        .lp-review-foot 底部保存区（不参与滚动，始终贴住卡片底部）
      三层都是 flex 子项且 min-height: 0，靠 flex 分配高度而不是 position: sticky，
      所以底栏不会盖住时间点、文本域末尾或错误提示。
    -->
    <div v-else class="lp-review">
      <div class="lp-review-ai">
        <AiReviewPanel :detail="detail" :saving="saving" @seek="emit('seek', $event)" @apply="applyAi" />
      </div>

      <div class="lp-review-main">
        <div class="lp-review-section-head">
          <span class="k">人工核验</span>
          <span class="hint">四个字段相互独立，取值不等于「内容为真」</span>
        </div>

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
        <div v-if="detail.claim" class="lp-review-claim">{{ detail.claim }}</div>

        <div class="lp-field-grid">
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
              <span class="hint">{{ answerStatus ? ANSWER_STATUS_LABEL[answerStatus] : '尚未核验' }}</span>
            </div>
            <!-- null（未核验）传空串：分段控件没有任何项被选中，不会假装已经选了「未找到回答」 -->
            <SegmentedField
              :model-value="answerStatus ?? ''"
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
        </div>

        <div class="lp-field">
          <div class="lp-field-label">
            <span>核验依据</span>
            <span class="lp-counter">{{ reasoning.length }} / {{ TEXT_LIMIT }}</span>
          </div>
          <el-input
            v-model="reasoning"
            type="textarea"
            :rows="3"
            :maxlength="TEXT_LIMIT"
            :disabled="saving"
            placeholder="填入核验依据，例如：01:05 处出现导入结果表格，演示过程完整。"
          />
        </div>

        <div class="lp-field">
          <div class="lp-field-label">
            <span>局限与风险</span>
            <span class="lp-counter">{{ limitations.length }} / {{ TEXT_LIMIT }}</span>
          </div>
          <el-input
            v-model="limitations"
            type="textarea"
            :rows="3"
            :maxlength="TEXT_LIMIT"
            :disabled="saving"
            placeholder="填入本次核验的局限或风险，例如：未展示 5000 条规模，性能无法确认。"
          />
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
          <div v-if="pointHint" class="lp-review-hint warn">{{ pointHint }}</div>
          <div class="lp-review-hint">
            起点 / 终点来自用例记录，只读；标记点随「保存核验结果」写入后端（0–7200 秒，最多
            {{ MAX_TIME_POINTS }} 个）。
          </div>
        </div>
      </div>

      <div class="lp-review-foot">
        <div class="lp-foot-actions">
          <button type="button" class="lp-btn lp-btn-primary" :disabled="!hasChanges || saving" @click="submit">
            {{ saving ? '保存中…' : '保存核验结果' }}
          </button>
          <button type="button" class="lp-btn" :disabled="saving" @click="emit('create-todo')">创建后续待办</button>
        </div>
        <div class="lp-foot-meta">
          <span v-if="hasChanges" class="is-dirty">{{ changedFields.length }} 项改动待保存</span>
          <span class="ts">最近保存 {{ formatDateTime(lastSavedAt ?? detail.updatedAt) }}</span>
        </div>
      </div>
    </div>
  </section>
</template>
