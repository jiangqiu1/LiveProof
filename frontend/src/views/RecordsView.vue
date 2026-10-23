<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { fetchCases, fetchDatasets, fetchMaterials, generateReviewReport } from '@/api'
import { describeError } from '@/api/client'
import type {
  AnswerStatus,
  CaseListItem,
  Dataset,
  EvidenceSufficiency,
  EvidenceType,
  Material,
  ReviewReport,
  ReviewStatus,
} from '@/types/api'
import {
  ANSWER_STATUS_LABEL,
  EVIDENCE_SUFFICIENCY_LABEL,
  EVIDENCE_TYPE_LABEL,
  REVIEW_STATUS_LABEL,
  formatDateTime,
  formatRange,
  reviewTone,
  sufficiencyTone,
} from '@/constants/meta'

const router = useRouter()

const reviewText = (value: string) => REVIEW_STATUS_LABEL[value as ReviewStatus]
const answerText = (value: string) => ANSWER_STATUS_LABEL[value as AnswerStatus]
const evidenceText = (value: string) => EVIDENCE_TYPE_LABEL[value as EvidenceType]
const sufficiencyText = (value: string) => EVIDENCE_SUFFICIENCY_LABEL[value as EvidenceSufficiency]
const cases = ref<CaseListItem[]>([])
const datasets = ref<Dataset[]>([])
const materials = ref<Material[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const keyword = ref('')
const reviewFilter = ref<ReviewStatus | 'all'>('all')

/** 素材范围：null = 全部素材。报告与表格共用同一范围，避免「表格一套、报告另一套」。 */
const materialId = ref<string | null>(null)
const materialLabel = computed(() => {
  if (!materialId.value) return '全部素材'
  const material = materials.value.find((item) => item.id === materialId.value)
  return material?.originalName ?? materialId.value
})

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return cases.value.filter((item) => {
    if (reviewFilter.value !== 'all' && item.reviewStatus !== reviewFilter.value) return false
    if (!kw) return true
    return `${item.question ?? ''} ${item.claim ?? ''} ${item.id}`.toLowerCase().includes(kw)
  })
})

const stats = computed(() => {
  const total = cases.value.length
  const reviewed = cases.value.filter((item) => item.reviewStatus === 'reviewed').length
  const sufficient = cases.value.filter((item) => item.evidenceSufficiency === 'sufficient').length
  const insufficient = cases.value.filter((item) => item.evidenceSufficiency === 'insufficient').length
  const operational = cases.value.filter((item) => item.evidenceType === 'operational_evidence').length
  return { total, reviewed, sufficient, insufficient, operational }
})

async function load() {
  loading.value = true
  error.value = null
  try {
    const [list, ds, mats] = await Promise.all([
      fetchCases('cf', materialId.value),
      fetchDatasets(),
      fetchMaterials(),
    ])
    cases.value = list
    datasets.value = ds
    materials.value = mats
  } catch (err) {
    error.value = describeError(err)
    ElMessage.error(error.value)
  } finally {
    loading.value = false
  }
}

/** 切换素材范围：只重拉用例，不清空已生成的报告（报告自带范围标签） */
async function onMaterialScopeChange(value: string | null) {
  materialId.value = value || null
  loading.value = true
  error.value = null
  try {
    cases.value = await fetchCases('cf', materialId.value)
  } catch (err) {
    error.value = describeError(err)
    ElMessage.error(error.value)
  } finally {
    loading.value = false
  }
}

function openCase(id: string) {
  void router.push({ path: '/', query: { case: id } })
}

/* ---------- AI 复盘报告 ---------- */

const report = ref<ReviewReport | null>(null)
const reportLoading = ref(false)
const reportError = ref<string | null>(null)
const reportVisible = ref(false)

async function generateReport() {
  if (reportLoading.value) return
  reportLoading.value = true
  reportError.value = null
  try {
    report.value = await generateReviewReport('cf', materialId.value)
    reportVisible.value = true
  } catch (err) {
    reportError.value = describeError(err)
    ElMessage.error(reportError.value)
  } finally {
    reportLoading.value = false
  }
}

function downloadReport() {
  const data = report.value
  if (!data) return
  const stamp = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  const name = `liveproof-report-${data.datasetId}-${stamp.getFullYear()}${pad(stamp.getMonth() + 1)}${pad(
    stamp.getDate(),
  )}-${pad(stamp.getHours())}${pad(stamp.getMinutes())}.md`
  const blob = new Blob([data.markdown], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = name
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
  ElMessage.success('已下载本次生成的 Markdown 报告')
}

onMounted(load)
</script>

<template>
  <div class="lp-page">
    <header class="lp-page-head">
      <div>
        <div class="lp-crumb">工作台 <span class="sep">/</span> 核验记录</div>
        <h1 class="lp-page-title">核验记录</h1>
        <p class="lp-page-sub">
          全部用例的四字段状态。任一取值组合都不等于「内容为真」，需人工逐条改判。
          素材范围同时作用于表格与复盘报告。
        </p>
      </div>
      <div class="lp-page-actions">
        <el-select
          :model-value="materialId"
          size="small"
          clearable
          placeholder="全部素材"
          style="width: 200px"
          @update:model-value="onMaterialScopeChange"
        >
          <el-option
            v-for="item in materials"
            :key="item.id"
            :label="`${item.originalName ?? item.id} · ${item.builtin ? '内置' : '保利威'}`"
            :value="item.id"
          />
        </el-select>
        <button type="button" class="lp-btn" :disabled="loading" @click="load">
          {{ loading ? '刷新中…' : '刷新' }}
        </button>
        <button
          type="button"
          class="lp-btn lp-btn-primary"
          :disabled="reportLoading || loading"
          @click="generateReport"
        >
          {{ reportLoading ? '生成中…' : '生成 AI 复盘报告' }}
        </button>
      </div>
    </header>

    <div class="lp-metric-row">
      <div class="lp-metric">
        <div class="k">用例总数</div>
        <div class="v">{{ stats.total }}<span class="u">条</span></div>
      </div>
      <div class="lp-metric">
        <div class="k">已核验</div>
        <div class="v">{{ stats.reviewed }}<span class="u">条</span></div>
      </div>
      <div class="lp-metric">
        <div class="k">证据充分</div>
        <div class="v">{{ stats.sufficient }}<span class="u">条</span></div>
      </div>
      <div class="lp-metric">
        <div class="k">证据不足</div>
        <div class="v">{{ stats.insufficient }}<span class="u">条</span></div>
      </div>
      <div class="lp-metric">
        <div class="k">实际操作证据</div>
        <div class="v">{{ stats.operational }}<span class="u">条</span></div>
      </div>
    </div>

    <section class="lp-card" style="flex: 1 1 auto; min-height: 0">
      <div class="lp-card-head">
        <div class="lp-card-title">
          {{ datasets[0]?.label ?? '数据集' }}
          <span style="margin-left: 8px; font-weight: 400; color: var(--lp-muted); font-size: 11.5px">
            范围：{{ materialLabel }}
          </span>
        </div>
        <div style="display: flex; gap: 8px; align-items: center">
          <el-input v-model="keyword" placeholder="搜索问题…" size="small" clearable style="width: 200px" />
          <el-select v-model="reviewFilter" size="small" style="width: 130px">
            <el-option label="全部进度" value="all" />
            <el-option
              v-for="(label, value) in REVIEW_STATUS_LABEL"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </div>
      </div>

      <div class="lp-page-body">
        <div v-if="error" class="lp-note warn" style="margin: 12px">{{ error }}</div>
        <el-table :data="filtered" size="small" style="width: 100%" empty-text="没有匹配的用例">
          <el-table-column type="index" label="#" width="48" />
          <el-table-column label="待核验问题" min-width="280">
            <template #default="{ row }">
              <div style="font-weight: 500; color: var(--lp-ink)">{{ row.question }}</div>
              <div style="font-size: 11.5px; color: var(--lp-muted-soft)">{{ row.id }}</div>
            </template>
          </el-table-column>
          <el-table-column label="演示时间" width="120">
            <template #default="{ row }">
              <span style="font-variant-numeric: tabular-nums">{{ formatRange(row.startSec, row.endSec) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="核验进度" width="104">
            <template #default="{ row }">
              <span class="lp-pill" :class="reviewTone(row.reviewStatus)">
                {{ reviewText(row.reviewStatus) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="回答状态" width="104">
            <template #default="{ row }">{{ answerText(row.answerStatus) }}</template>
          </el-table-column>
          <el-table-column label="证据类型" width="130">
            <template #default="{ row }">{{ evidenceText(row.evidenceType) }}</template>
          </el-table-column>
          <el-table-column label="充分性" width="120">
            <template #default="{ row }">
              <span class="lp-pill" :class="sufficiencyTone(row.evidenceSufficiency)">
                {{ sufficiencyText(row.evidenceSufficiency) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="150">
            <template #default="{ row }">
              <span style="color: var(--lp-muted)">{{ formatDateTime(row.updatedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="92" fixed="right">
            <template #default="{ row }">
              <button type="button" class="lp-btn lp-btn-text" style="height: 24px" @click="openCase(row.id)">
                去核验
              </button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </section>

    <el-dialog
      v-model="reportVisible"
      title="AI 复盘报告"
      width="880px"
      top="5vh"
      class="lp-report-dialog"
      :close-on-click-modal="false"
    >
      <div v-if="report" class="lp-report-body">
        <div class="lp-report-meta">
          <span class="lp-pill mint">{{ report.datasetLabel }}</span>
          <span class="lp-pill" :class="report.aiUsed ? 'mint' : 'ghost'">
            {{ report.aiUsed ? '含 AI 归纳' : '仅事实（未启用 AI 归纳）' }}
          </span>
          <span>生成时间 {{ report.generatedAt }}</span>
          <span>事项 {{ report.stats.total }} · 已核验 {{ report.stats.reviewed }} · 未检查 {{ report.stats.unreviewed }}</span>
        </div>
        <div v-if="report.notes.length" class="lp-note" style="margin: 8px 0">
          <div v-for="(note, index) in report.notes" :key="index">· {{ note }}</div>
        </div>
        <div class="lp-report-hint">
          Markdown 源码预览（下载后可用任意 Markdown 阅读器打开）；报告内容来自数据库已保存结果与待办。
        </div>
        <pre class="lp-report-pre">{{ report.markdown }}</pre>
      </div>
      <template #footer>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span style="font-size: 11.5px; color: var(--lp-muted-soft)">
            未保存的编辑状态与未采纳的 AI 建议不会出现在报告中。
          </span>
          <span style="display: flex; gap: 8px">
            <button type="button" class="lp-btn" @click="reportVisible = false">关闭</button>
            <button type="button" class="lp-btn lp-btn-primary" :disabled="!report" @click="downloadReport">
              下载 Markdown
            </button>
          </span>
        </div>
      </template>
    </el-dialog>
  </div>
</template>
