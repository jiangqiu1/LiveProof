<script setup lang="ts">
import { computed, ref } from 'vue'
import LpSkeletonList from '@/components/LpSkeletonList.vue'
import LpState from '@/components/LpState.vue'
import type { CaseListItem, ReviewStatus } from '@/types/api'
import { REVIEW_STATUS_LABEL, formatRange, reviewTone } from '@/constants/meta'

const props = defineProps<{
  cases: CaseListItem[]
  activeId: string | null
  loading: boolean
  error: string | null
  /** 当前素材是否就绪（未就绪时禁止新增与导入，与后端 409 口径一致） */
  canCreate?: boolean
  /** 未就绪时的原因说明 */
  blockedReason?: string | null
}>()

const emit = defineEmits<{
  (e: 'select', id: string): void
  (e: 'create'): void
  (e: 'import'): void
}>()

const keyword = ref('')
const reviewFilter = ref<ReviewStatus | 'all'>('all')

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return props.cases.filter((item) => {
    if (reviewFilter.value !== 'all' && item.reviewStatus !== reviewFilter.value) return false
    if (!kw) return true
    const haystack = `${item.question ?? ''} ${item.claim ?? ''} ${item.id}`.toLowerCase()
    return haystack.includes(kw)
  })
})

function indexOf(id: string) {
  const index = props.cases.findIndex((item) => item.id === id)
  return index >= 0 ? index + 1 : 0
}

/**
 * 回答状态的显示口径。
 *
 * <p>两件事必须分开：
 * <ul>
 *   <li>{@code answerStatus === null} —— 还没判定过（V8 起新建/导入都是 NULL），不显示标签；</li>
 *   <li>{@code answerStatus === 'not_found'} —— 已经判定「视频里没有找到回答」，照实显示。</li>
 * </ul>
 *
 * <p>{@code reviewStatus === 'unreviewed'} 的兜底是给历史数据留的：V8 之前新建的事项
 * 会在库里留下 not_found 占位值，清理迁移之外的环境仍应显示成未核验。
 */
function answerLabel(item: CaseListItem): string | null {
  if (!item.answerStatus) return null
  if (item.reviewStatus === 'unreviewed') return null
  if (item.answerStatus === 'answered') return '已回答'
  if (item.answerStatus === 'partially_answered') return '部分回答'
  return '未找到回答'
}

function answerTone(item: CaseListItem): string {
  if (!item.answerStatus) return 'ghost'
  if (item.answerStatus === 'answered') return 'mint'
  if (item.answerStatus === 'partially_answered') return 'ink'
  return 'warn'
}

function clearFilters() {
  keyword.value = ''
  reviewFilter.value = 'all'
}
</script>

<template>
  <section class="lp-card">
    <div class="lp-card-head">
      <div class="lp-card-title">待核验问题</div>
      <span class="lp-pill ghost">{{ cases.length }} 条</span>
    </div>

    <div style="padding: 10px 12px 6px; display: flex; flex-direction: column; gap: 8px">
      <el-input
        v-model="keyword"
        placeholder="搜索问题标题…"
        size="small"
        clearable
      />
      <el-select v-model="reviewFilter" size="small" style="width: 100%">
        <el-option label="全部状态" value="all" />
        <el-option
          v-for="(label, value) in REVIEW_STATUS_LABEL"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
      <div style="display: flex; gap: 6px">
        <button
          type="button"
          class="lp-btn lp-btn-primary"
          style="flex: 1 1 0; height: 26px; padding: 0 8px; font-size: 12px"
          :disabled="canCreate === false"
          :title="canCreate === false ? (blockedReason ?? '当前不能新增') : '手动新增一条核验事项'"
          @click="emit('create')"
        >
          新增事项
        </button>
        <button
          type="button"
          class="lp-btn"
          style="flex: 1 1 0; height: 26px; padding: 0 8px; font-size: 12px"
          :disabled="canCreate === false"
          :title="canCreate === false ? (blockedReason ?? '当前不能导入') : '从 CSV 批量导入'"
          @click="emit('import')"
        >
          导入 CSV
        </button>
      </div>
      <div v-if="canCreate === false && blockedReason" class="lp-note warn" style="font-size: 11px">
        {{ blockedReason }}
      </div>
    </div>

    <div class="lp-scroll" style="flex: 1 1 auto; padding: 2px 8px 10px">
      <!-- 失败整块替换列表：请求失败 ≠ 没有问题 -->
      <LpState v-if="error" variant="error" compact title="事项列表加载失败" :description="error" />
      <LpSkeletonList v-else-if="loading" :rows="4" />
      <!-- 搜索/筛选无命中 ≠ 没有问题：给清除入口 -->
      <LpState
        v-else-if="filtered.length === 0 && cases.length > 0"
        variant="search"
        compact
        title="没有匹配的问题"
        :description="keyword.trim() ? `没有标题包含「${keyword.trim()}」的事项，换个关键词试试。` : '当前筛选状态下没有事项。'"
      >
        <template #actions>
          <button
            type="button"
            class="lp-btn"
            style="height: 26px; padding: 0 12px; font-size: 12px"
            @click="clearFilters"
          >
            清除搜索与筛选
          </button>
        </template>
      </LpState>
      <LpState
        v-else-if="filtered.length === 0"
        compact
        title="还没有核验事项"
        description="这个素材下还没有录入任何待核验的产品承诺。"
      />
      <div
        v-for="item in filtered"
        :key="item.id"
        class="lp-case-item"
        :class="{ 'is-active': item.id === activeId }"
        @click="emit('select', item.id)"
      >
        <span class="lp-case-index">{{ indexOf(item.id) }}</span>
        <div class="lp-case-body">
          <div class="lp-case-q">{{ item.question ?? item.id }}</div>
          <div class="lp-case-meta">
            <span class="time">{{ formatRange(item.startSec, item.endSec) }}</span>
            <span class="lp-pill" :class="reviewTone(item.reviewStatus)">
              {{ REVIEW_STATUS_LABEL[item.reviewStatus] }}
            </span>
            <span
              v-if="answerLabel(item)"
              class="lp-pill"
              :class="answerTone(item)"
            >
              {{ answerLabel(item) }}
            </span>
            <span v-else class="lp-pill ghost" title="新增后默认未核验；此处的库内占位值不代表视频没有这样说">
              尚未核验
            </span>
            <span v-if="item.source === 'csv'" class="lp-pill ghost">CSV</span>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>