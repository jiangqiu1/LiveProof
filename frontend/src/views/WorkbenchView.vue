<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
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
const router = useRouter()

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

async function handleSwitchSource(source: SubtitleSource) {
  await store.setSubtitleSource(source)
  if (store.subtitleError) {
    ElMessage.warning(store.subtitleError)
  }
}

/**
 * 切换前的草稿保护。
 *
 * <p>右栏的编辑态只存在于 {@code ReviewPanel} 内部，切换事项会让 {@code detail} 变化，
 * 面板随之用新事项的字段整体覆盖本地状态；未保存的改判、核验依据、局限与风险、时间点标记
 * 都会消失且无法撤销。因此任何会改变当前选中事项的入口都必须先经过这里。
 *
 * <p>返回 false 时调用方必须立即返回，不得触碰 store、播放器或任何编辑态。
 *
 * @param label 切换对象的名称，用于提示文案
 * @returns true 表示可以继续切换（本来就无改动，或用户明确选择放弃）
 */
async function confirmDiscardDraft(label: string): Promise<boolean> {
  if (!dirty.value) return true
  try {
    await ElMessageBox.confirm(
      `切换${label}将放弃当前尚未保存的核验内容。`,
      '存在未保存的修改',
      {
        type: 'warning',
        confirmButtonText: '放弃修改并切换',
        cancelButtonText: '继续编辑',
      },
    )
    return true
  } catch {
    // 取消或点关闭：不改变任何状态，调用方据此提前返回
    return false
  }
}

/**
 * 把「当前实际选中的事项」写回地址栏的 {@code case} 参数。
 *
 * <p>为什么需要：左栏切换只改 store，地址栏仍是旧事项，用户按 F5 会被
 * {@code onMounted} 里读 {@code ?case=} 的逻辑拉回旧事项——看起来像「刚编辑的内容丢了」。
 *
 * <p>导航语义（两种入口统一按这条规则走，不再逐处临时决定）：
 * <ul>
 *   <li>{@code push}：用户主动选中某条事项（左栏点击）。产生一条历史记录，
 *       浏览器「后退」回到上一个看过的事项，符合列表选择的直觉。</li>
 *   <li>{@code replace}：系统自动纠正地址栏（首次进入自动选中第一条、切换素材范围后重选、
 *       取消切换回滚、新增后自动跳转）。用户没做过这次选择，不该留下可后退的历史条目。</li>
 * </ul>
 *
 * <p>与 {@code watch(route.query.case)} 不会互相触发成环：本函数写完 URL 后，
 * 监听器拿到的值与 {@code store.activeCaseId} 相同，会在第一行直接返回。
 */
async function syncCaseQuery(id: string | null, mode: 'push' | 'replace') {
  const current = typeof route.query.case === 'string' ? route.query.case : null
  if (current === (id ?? null)) {
    rememberLocation()
    return
  }
  const query = { ...route.query }
  if (id) {
    query.case = id
  } else {
    delete query.case
  }
  if (mode === 'push') {
    await router.push({ query })
  } else {
    await router.replace({ query })
  }
  rememberLocation()
}

/**
 * 记录「上次停留的位置」，供首页「继续你的核验工作」直达。
 *
 * <p>只写素材 id 与事项 id，不含任何用户内容，也不作为核验数据使用。
 * 状态写失败（隐私模式、配额满）只意味着首页失去直达能力，不影响核验，
 * 因此静默忽略，不弹提示打扰。
 *
 * <p>放在这里而不是 {@code watch(store.activeCaseId)}：本函数只在「地址栏与右栏
 * 已经一致」的时刻被调用，记录的永远是用户真正看到的那一条；store 单独变化
 * （比如加载中被重置）时不该污染这个记录。
 */
const LAST_KEY = 'lp.lastLocation'

function rememberLocation() {
  const materialId = store.detail?.materialId ?? store.materialId ?? null
  const caseId = store.activeCaseId
  if (!materialId || !caseId) return
  try {
    localStorage.setItem(LAST_KEY, JSON.stringify({ materialId, caseId }))
  } catch {
    // 忽略：记录位置属于锦上添花，失败不阻塞核验
  }
}

/**
 * 用户主动切换核验事项（左栏点击、路由跳转、新增后自动选中都走这里）。
 *
 * <p>已有未保存修改时先确认；取消则完全不动 store、播放器与地址栏。
 */
async function selectCase(id: string): Promise<boolean> {
  // 点的正是当前事项：不重拉详情。重拉会用服务端旧值覆盖本地草稿，同样属于静默丢失。
  if (id === store.activeCaseId) {
    // 地址栏可能还没跟上（例如直接访问 "/" 后自动选中的第一条），补一次 replace
    await syncCaseQuery(id, 'replace')
    return true
  }
  if (!(await confirmDiscardDraft('核验事项'))) return false
  if (!(await switchCase(id))) {
    // 打不开：只有当后端明确说「不存在」时才回退，别把网络故障当成事项被删
    await recoverInvalidCase(id)
    return false
  }
  // 必须等 switchCase 完成再写 URL：否则 route 监听器会看到「URL 已变、store 未变」，
  // 把这次点击当成外部跳转，再弹一次确认框
  await syncCaseQuery(id, 'push')
  return true
}

/**
 * 只做切换、不做草稿检查的底层动作。
 *
 * <p>只允许在已经确认过 {@code dirty} 的路径上调用（见 {@code selectCase}），
 * 直接调用会绕过保护。
 *
 * @returns true 表示右栏已经拿到了目标事项的详情；false 表示这条 id 打不开，
 *          由调用方决定是回退还是保留地址栏（见 {@link recoverInvalidCase}）
 */
async function switchCase(id: string | null): Promise<boolean> {
  player.clear()
  await store.selectCase(id)
  if (id === null) return true
  if (!store.detail) return false
  const start = store.detail.startSec
  if (start !== null && start !== undefined) {
    player.seekTo(Math.max(0, start))
  }
  return true
}

/**
 * 地址栏指向一条打不开的事项时的兜底。
 *
 * <p>只处理「这条事项真的不存在」（后端 404 {@code CASE_NOT_FOUND}）：
 * <ul>
 *   <li>列表里还有别的事项 → 切到列表第一条，地址栏 replace 成新的 id；
 *   <li>列表本身为空 → 进入空状态并删掉 {@code ?case=}，右栏显示「从左侧选择」而不是加载失败；
 *   <li>网络 / 后端故障 → 什么都不动，保留用户的深链接等重试，否则一次闪断就把地址冲掉。
 * </ul>
 *
 * <p>回退一律用 {@code replace}：用户并没有主动选择这条新事项，不该留下可后退的历史条目。
 * 走到这里时草稿保护已经通过（{@code selectCase} / 路由监听都在切换前确认过），
 * 回退不会静默丢掉未保存的改判。
 */
async function recoverInvalidCase(invalidId: string) {
  if (store.detailErrorCode !== 'CASE_NOT_FOUND') return
  const fallback = store.cases.find((item) => item.id !== invalidId)?.id ?? null
  await switchCase(fallback)
  await syncCaseQuery(fallback, 'replace')
  ElMessage.warning(
    fallback
      ? `核验事项「${invalidId}」不存在或已被删除，已切回列表中的第一条`
      : `核验事项「${invalidId}」不存在或已被删除，当前没有可打开的事项`,
  )
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

function openTodoDialog() {
  todoDialogVisible.value = true
}

/**
 * 切换素材范围。
 *
 * <p>{@code setMaterialScope} 会直接把 {@code activeCaseId} 与 {@code detail} 置空，
 * 当前事项连同未保存草稿一起消失，所以必须先过草稿保护。
 *
 * <p>下拉框是受控的：用户点击后 Element Plus 会先发出新的 modelValue 再等 store 更新，
 * 取消时 store 保持原值，组件靠 {@code modelValue} 的 watch 自行回退到旧标签。
 */
async function onMaterialScopeChange(value: string | null) {
  if (value === store.materialId) return
  if (!(await confirmDiscardDraft('素材范围'))) return
  player.clear()
  await store.setMaterialScope(value)
  // 换范围会重选事项（通常落到新范围的第一条），旧 case 参数已经失效，必须同步改掉
  await syncCaseQuery(store.activeCaseId, 'replace')
}

/**
 * 新增事项后同步左栏。
 *
 * <p>{@code NewCaseDialog} 直连创建接口写入，store 里的 {@code cases} 仍是进页面时的快照，
 * 必须显式重拉再选中新行；否则左栏看不到刚建的事项，后续核验与加待办都无从下手。
 * 这里不复用 {@code store.addCase}，因为它会再创建一次，属于重复写入。
 *
 * <p>此时事项<b>已经写入后端</b>，所以草稿保护只决定「是否跳过去」，不能撤销创建：
 * 用户选择继续编辑时新事项保留在左栏，当前事项与草稿都不动。
 */
async function onCaseCreated(created: CaseDetail) {
  await store.reloadCases({ keepActive: true })
  if (await confirmDiscardDraft('新事项')) {
    await switchCase(created.id)
    // 新建后自动跳转属于系统行为，用 replace，别给「后退」留下一条用户没点过的历史
    await syncCaseQuery(created.id, 'replace')
  }
  await refreshPlaySource()
}

/**
 * 导入完成。
 *
 * <p>导入不会自动选中新事项（与新增不同），所以这里不主动切换，避免无改动时也弹确认框。
 * 但 {@code reloadCases} 内部有一条隐式切换：当前事项不在新列表里时会自动跳到第一条，
 * 那条路径同样会覆盖草稿，因此要求它保留当前选中项，把切换权留给本组件的保护逻辑。
 */
async function onImported() {
  await store.reloadCases({ keepActive: true })
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
  } else {
    // 没有指定 case 时由 store 自动选中第一条：把它 replace 进地址栏，
    // 让「刷新后停在哪一条」永远由 URL 决定，而不是靠运气
    await syncCaseQuery(store.activeCaseId, 'replace')
  }
  await refreshPlaySource()
})

/**
 * 地址栏的 {@code case} 变化 → 切换当前事项（深链接、前进 / 后退、记录页跳转都走这里）。
 *
 * <p>这条路径的麻烦处在于 URL 已经先变了：用户取消时必须把 query 改回当前事项，
 * 否则地址栏与右栏显示的是两个不同事项，刷新后会跳到另一个（并以草稿丢失为代价）。
 * 回退用 {@code replace} 避免污染浏览器历史。
 *
 * <p>只在本页面仍处于激活状态时响应：离开工作台时路由的 query 也会变，
 * 不加这条判断会在别的页面追加 {@code case} 参数，反过来污染那条导航。
 */
watch(
  () => route.query.case,
  async (value) => {
    if (route.name !== 'workbench') return
    // 地址栏没有 case（点侧栏「工作台」重新进入、手动清空参数）：不猜用户想去哪条，
    // 把当前选中的事项写回去，保证 URL 与右栏始终描述同一件事
    if (typeof value !== 'string' || !value) {
      if (store.activeCaseId) {
        await syncCaseQuery(store.activeCaseId, 'replace')
      }
      return
    }
    if (value === store.activeCaseId) return
    if (!(await confirmDiscardDraft('核验事项'))) {
      await syncCaseQuery(store.activeCaseId, 'replace')
      return
    }
    if (!(await switchCase(value))) {
      await recoverInvalidCase(value)
    }
  },
)
</script>

<template>
  <div class="lp-page">
    <header class="lp-page-head lp-rise">
      <div>
        <div class="lp-crumb">首页 <span class="sep">/</span> 工作台 <span class="sep">/</span> 视频核验</div>
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
      </div>
    </header>

    <div class="lp-workbench">
      <CaseListPanel
        class="lp-rise"
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

      <div
        class="lp-rise lp-d1"
        style="display: flex; flex-direction: column; gap: 14px; min-height: 0"
      >
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
        class="lp-rise lp-d2"
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
