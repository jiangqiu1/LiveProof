<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import CaseListPanel from '@/components/CaseListPanel.vue'
import VideoPlayer from '@/components/VideoPlayer.vue'
import SubtitlePanel from '@/components/SubtitlePanel.vue'
import ReviewPanel from '@/components/ReviewPanel.vue'
import TodoFormDialog from '@/components/TodoFormDialog.vue'
import NewCaseDialog from '@/components/NewCaseDialog.vue'
import CsvImportDialog from '@/components/CsvImportDialog.vue'
import { useWorkbenchStore } from '@/stores/workbench'
import { usePlayerStore } from '@/stores/player'
import { describeError } from '@/api/client'
import type { CaseDetail, CasePatchPayload, Material, SubtitleSource } from '@/types/api'

const store = useWorkbenchStore()
const player = usePlayerStore()
const route = useRoute()

const listRef = ref<InstanceType<typeof CaseListPanel> | null>(null)
const panelRef = ref<InstanceType<typeof ReviewPanel> | null>(null)
const dirty = ref(false)
const todoDialogVisible = ref(false)
const newCaseVisible = ref(false)
const importVisible = ref(false)

/** 当前素材的播放源：云端素材取保利威 CDN 直链，内置素材回退 /media */
const playUrl = ref<string | null>(null)
const playError = ref<string | null>(null)

const detail = computed(() => store.detail)
const windowStart = computed(() => detail.value?.startSec ?? null)
const windowEnd = computed(() => detail.value?.endSec ?? null)

/**
 * 素材下拉选项。
 *
 * <p>必须带上来源标记：上传的云端素材完全可能与内置素材同名（都叫 candidateflow-demo.mp4），
 * 只显示文件名时两个选项长得一模一样，用户无法判断自己核的是哪一段视频。
 */
const materialOptions = computed(() =>
  store.materials.map((item: Material) => {
    const name = item.originalName ?? item.id
    const origin = item.builtin ? '内置' : '保利威'
    const state = item.status === 'ready' ? '' : `（${item.status}）`
    return { value: item.id, label: `${name} · ${origin}${state}` }
  }),
)

/** 新增/导入的目标素材：优先选中用例归属的素材，其次页头筛选 */
const targetMaterialId = computed(() => detail.value?.materialId ?? store.materialId ?? null)

const targetMaterial = computed(
  () => store.materials.find((item) => item.id === targetMaterialId.value) ?? null,
)

const canCreate = computed(() => targetMaterial.value?.status === 'ready')

const blockedReason = computed(() => {
  if (!targetMaterialId.value) return '未选择素材：核验事项必须归属于某个素材'
  if (!targetMaterial.value) return '素材信息尚未加载，请稍候'
  if (targetMaterial.value.status === 'transcoding') return '保利威正在转码，就绪后才能新增或导入核验事项'
  if (targetMaterial.value.status === 'failed') return '素材转码失败，请重新上传后再新增核验事项'
  return null
})

/** 头部标记如实反映当前字幕来源，避免"本地演示"标签在云端来源下说错话 */
const sourceBadge = computed(() =>
  store.subtitleSource === 'cloud'
    ? { label: '智能字幕（云端）', hint: '字幕来自保利威智能字幕任务，仍须人工核对' }
    : { label: '本地演示', hint: '字幕与视频均来自本地素材，未调用云端服务' },
)

async function handleSwitchSource(source: SubtitleSource) {
  await store.setSubtitleSource(source)
  if (store.subtitleError) {
    ElMessage.warning(store.subtitleError)
  }
}

async function selectCase(id: string) {
  player.clear()
  await store.selectCase(id)
  const start = store.detail?.startSec
  if (start !== null && start !== undefined) {
    player.seekTo(Math.max(0, start))
  }
}

async function handleSave(payload: CasePatchPayload) {
  try {
    await store.saveCase(payload)
    ElMessage.success('核验结果已保存')
  } catch (error) {
    ElMessage.error(describeError(error))
  }
}

function seek(sec: number) {
  player.seekTo(sec)
}

async function focusSearch() {
  await nextTick()
  listRef.value?.focusSearch()
}

function openTodoDialog() {
  todoDialogVisible.value = true
}

async function onMaterialScopeChange(value: string | null) {
  player.clear()
  await store.setMaterialScope(value)
}

/**
 * 新增事项后同步左栏。
 *
 * <p>{@code NewCaseDialog} 直连创建接口写入，store 里的 {@code cases} 仍是进页面时的快照，
 * 必须显式重拉再选中新行；否则左栏看不到刚建的事项，后续核验与加待办都无从下手。
 * 这里不复用 {@code store.addCase}，因为它会再创建一次，属于重复写入。
 */
async function onCaseCreated(created: CaseDetail) {
  await store.reloadCases()
  await store.selectCase(created.id)
  await refreshPlaySource()
}

async function onImported() {
  await store.reloadCases()
  await refreshPlaySource()
  importVisible.value = false
}

/**
 * 解析当前素材的播放地址。
 *
 * <p>云端素材必须走 play 接口，内置素材继续 /media；
 * 两条轨道互不替代，取不到就显示错误，不静默播放别的视频。
 */
async function refreshPlaySource() {
  playError.value = null
  const material = targetMaterial.value
  if (!material) {
    playUrl.value = null
    return
  }
  const source = await store.resolvePlaySource(material.id, material.fileName)
  if (material.storage === 'polyv') {
    playUrl.value = source
    playError.value = store.playError
  } else {
    playUrl.value = null
    playError.value = store.playError
  }
}

const defaultTodoTitle = computed(() => {
  const question = detail.value?.question
  return question ? `补录演示：${question}` : ''
})

watch(
  () => targetMaterialId.value,
  async () => {
    await refreshPlaySource()
  },
)

onMounted(async () => {
  await store.ensureMaterials()
  const presetMaterial = typeof route.query.material === 'string' ? route.query.material : null
  if (presetMaterial) {
    store.materialId = presetMaterial
  }
  await store.loadWorkspace('cf')
  const preset = typeof route.query.case === 'string' ? route.query.case : null
  if (preset && preset !== store.activeCaseId) {
    await selectCase(preset)
  }
  await refreshPlaySource()
})

// 从「核验记录」页点击进入时按 query 切换用例
watch(
  () => route.query.case,
  async (value) => {
    if (typeof value === 'string' && value && value !== store.activeCaseId) {
      await selectCase(value)
    }
  },
)
</script>

<template>
  <div class="lp-page">
    <header class="lp-page-head">
      <div>
        <div class="lp-crumb">工作台 <span class="sep">/</span> 视频核验</div>
        <h1 class="lp-page-title">视频证据核验</h1>
        <p class="lp-page-sub">从演示内容中定位证据，核验每一项功能承诺。</p>
      </div>
      <div class="lp-page-actions">
        <el-select
          :model-value="store.materialId"
          size="small"
          style="width: 240px"
          placeholder="全部素材"
          clearable
          @update:model-value="onMaterialScopeChange"
        >
          <el-option
            v-for="option in materialOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <span class="lp-pill mint" :title="sourceBadge.hint">
          <span class="lp-dot"></span>{{ sourceBadge.label }}
        </span>
        <button type="button" class="lp-btn lp-btn-icon" title="搜索问题" @click="focusSearch">
          <svg
            width="15"
            height="15"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.9"
            stroke-linecap="round"
          >
            <circle cx="11" cy="11" r="6.5" />
            <path d="M16 16l4 4" />
          </svg>
        </button>
        <button
          type="button"
          class="lp-btn lp-btn-primary"
          :disabled="!dirty || store.saving"
          @click="panelRef?.submit()"
        >
          {{ store.saving ? '保存中…' : '保存核验结果' }}
        </button>
      </div>
    </header>

    <div class="lp-workbench">
      <CaseListPanel
        ref="listRef"
        :cases="store.cases"
        :active-id="store.activeCaseId"
        :loading="store.loading.cases"
        :error="store.errors.cases"
        :can-create="canCreate"
        :blocked-reason="blockedReason"
        @select="selectCase"
        @create="newCaseVisible = true"
        @import="importVisible = true"
      />

      <div style="display: flex; flex-direction: column; gap: 14px; min-height: 0">
        <section class="lp-card" style="flex: 0 0 auto">
          <div class="lp-card-head">
            <div class="lp-card-title">演示视频</div>
            <span class="lp-pill ghost">
              {{ targetMaterial?.originalName ?? store.videoName ?? '未选择' }}
            </span>
          </div>
          <VideoPlayer
            :src="targetMaterial && targetMaterial.storage === 'local' ? targetMaterial.fileName : store.videoName"
            :play-url="playUrl"
          />
          <div v-if="playError" class="lp-note warn" style="margin: 0 14px 12px">
            {{ playError }}
          </div>
        </section>

        <SubtitlePanel
          :cues="store.cues"
          :loading="store.loading.subtitles"
          :error="store.subtitleError"
          :window-start="windowStart"
          :window-end="windowEnd"
          :source="store.subtitleSource"
          :cloud-ready="Boolean(store.cloudTask)"
          :subtitle-file="store.subtitle?.subtitleFile ?? null"
          :video-name="store.videoName ?? (targetMaterial?.fileName ?? null)"
          @seek="seek"
          @switch-source="handleSwitchSource"
        />
      </div>

      <ReviewPanel
        ref="panelRef"
        :detail="detail"
        :saving="store.saving"
        :loading="store.loading.detail"
        :error="store.errors.detail"
        :last-saved-at="store.lastSavedAt"
        @save="handleSave"
        @create-todo="openTodoDialog"
        @seek="seek"
        @dirty="dirty = $event"
      />
    </div>

    <TodoFormDialog
      v-model="todoDialogVisible"
      :todo="null"
      :cases="store.cases"
      :preset-case-id="store.activeCaseId"
      :default-title="defaultTodoTitle"
    />

    <NewCaseDialog
      v-model="newCaseVisible"
      :material-id="targetMaterialId"
      :materials="store.materials"
      @created="onCaseCreated"
    />

    <CsvImportDialog
      v-model="importVisible"
      :material-id="targetMaterialId"
      :materials="store.materials"
      @imported="onImported"
    />
  </div>
</template>
