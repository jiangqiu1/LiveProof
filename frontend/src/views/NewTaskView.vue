<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  createSmartSubtitleTask,
  fetchMaterialCapabilities,
  fetchMaterials,
  fetchSmartSubtitleTasks,
  refreshSmartSubtitleTask,
  uploadMaterialSubtitle,
} from '@/api'
import { describeError } from '@/api/client'
import type { Material, MaterialCapabilities, SmartSubtitleTask } from '@/types/api'
import MaterialUploader from '@/components/MaterialUploader.vue'
import NewCaseDialog from '@/components/NewCaseDialog.vue'
import CsvImportDialog from '@/components/CsvImportDialog.vue'
import AiCandidateDialog from '@/components/AiCandidateDialog.vue'
import LpSkeletonList from '@/components/LpSkeletonList.vue'
import LpState from '@/components/LpState.vue'

const router = useRouter()

const STEP_TITLES = ['选择素材', '准备字幕', '录入核验事项']

const step = ref(0)
const materials = ref<Material[]>([])
const capabilities = ref<MaterialCapabilities | null>(null)
const selectedId = ref<string | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

/* ---------- 第 2 步：字幕 ---------- */
type SubtitleMode = 'cloud' | 'srt' | 'none'
const subtitleMode = ref<SubtitleMode>('none')
const cloudTask = ref<SmartSubtitleTask | null>(null)
const cloudBusy = ref(false)
const cloudError = ref<string | null>(null)
const srtBusy = ref(false)
const srtInput = ref<HTMLInputElement | null>(null)

let pollTimer: number | null = null
let pollAttempt = 0
const MAX_POLL_ATTEMPTS = 20
const POLL_INTERVAL_MS = 3000

/* ---------- 第 3 步：核验事项 ---------- */
const caseDialog = ref(false)
const csvDialog = ref(false)
const aiDialog = ref(false)
const aiCreated = ref(0)
const manualCreated = ref(0)
const importedCount = ref(0)

const readyMaterials = computed(() => materials.value.filter((item) => item.status === 'ready'))
const selected = computed(() => materials.value.find((item) => item.id === selectedId.value) ?? null)
const selectedReady = computed(() => selected.value?.status === 'ready')

/**
 * 云端字幕任务用的 key：内置素材取文件名，云端素材取素材 id（与 SmartSubtitleService 口径一致）。
 * 两者都不能为空，否则后端无法定位 VID。
 */
const subtitleKey = computed(() => selected.value?.fileName ?? selected.value?.id ?? null)

const cloudReady = computed(() => cloudTask.value?.status === 'ready')
const hasSubtitle = computed(() => cloudReady.value || Boolean(selected.value?.srtFileName))

const cloudStatusLabel = computed(() => {
  const task = cloudTask.value
  if (!task) return '尚未创建任务'
  switch (task.status) {
    case 'ready':
      return `已就绪（${task.subtitleCount ?? 0} 条字幕）`
    case 'processing':
      return '生成中'
    case 'created':
      return '已创建，等待查询'
    case 'failed':
      return '生成失败'
    default:
      return '云端不存在'
  }
})

const caseCountLabel = computed(() => {
  const parts: string[] = []
  if (aiCreated.value > 0) parts.push(`AI 候选 ${aiCreated.value} 条`)
  if (manualCreated.value > 0) parts.push(`手动新增 ${manualCreated.value} 条`)
  if (importedCount.value > 0) parts.push(`CSV 导入 ${importedCount.value} 条`)
  return parts.length > 0 ? parts.join(' · ') : '还没有录入核验事项'
})

const canNext = computed(() => {
  if (step.value === 0) return selectedReady.value
  return true
})

const nextHint = computed(() => {
  if (step.value !== 0) return ''
  if (!selected.value) return '请选择一个素材，或先上传一段演示视频'
  if (!selectedReady.value) return '素材还在转码中，转码完成后才能录入核验事项'
  return ''
})

async function load() {
  loading.value = true
  error.value = null
  try {
    const [list, caps] = await Promise.all([fetchMaterials(), fetchMaterialCapabilities()])
    materials.value = list
    capabilities.value = caps
    if (!selectedId.value) {
      const firstReady = list.find((item) => item.status === 'ready')
      selectedId.value = firstReady?.id ?? null
    }
    await loadExistingTask()
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

/**
 * 读取该素材已登记的智能字幕任务（只读本地登记，不查云端、不消耗资源点）。
 *
 * <p>不读会导致页面显示「尚未创建任务」，而实际上任务早就就绪——用户以为必须再点一次创建，
 * 也就看不出「这一步其实已经完成了」。后端 createOrReuse 会复用、不会重复消耗，
 * 但界面必须先说实话。
 */
async function loadExistingTask() {
  const key = subtitleKey.value
  cloudError.value = null
  if (!key) {
    cloudTask.value = null
    return
  }
  try {
    const tasks = await fetchSmartSubtitleTasks(key)
    cloudTask.value = tasks.find((task) => task.status === 'ready') ?? tasks[0] ?? null
  } catch (e) {
    cloudTask.value = null
    cloudError.value = describeError(e)
  }
}

function stopPoll() {
  if (pollTimer !== null) {
    window.clearTimeout(pollTimer)
    pollTimer = null
  }
}

/** 轮询云端任务：只读真实状态，不伪造进度 */
function pollTask(taskId: string) {
  stopPoll()
  pollAttempt += 1
  if (pollAttempt > MAX_POLL_ATTEMPTS) {
    cloudError.value = '等待超时（约 1 分钟仍未就绪），可在素材页稍后复查，也可先上传 SRT'
    return
  }
  pollTimer = window.setTimeout(async () => {
    try {
      const updated = await refreshSmartSubtitleTask(taskId)
      cloudTask.value = updated
      if (updated.status === 'ready') {
        ElMessage.success(`智能字幕已就绪，共 ${updated.subtitleCount ?? 0} 条`)
        return
      }
      if (updated.status === 'failed' || updated.status === 'not_found') {
        cloudError.value = updated.errorMessage ?? '云端任务未成功，可改用上传 SRT'
        return
      }
      pollTask(taskId)
    } catch (e) {
      cloudError.value = describeError(e)
    }
  }, POLL_INTERVAL_MS)
}

async function createCloudTask() {
  const key = subtitleKey.value
  if (!key || cloudBusy.value) return
  cloudBusy.value = true
  cloudError.value = null
  stopPoll()
  pollAttempt = 0
  try {
    const task = await createSmartSubtitleTask(key)
    cloudTask.value = task
    if (task.status === 'ready') {
      ElMessage.success(`已复用就绪的智能字幕任务（${task.subtitleCount ?? 0} 条）`)
      return
    }
    if (task.taskId) {
      ElMessage.success(task.reused ? '已复用既有任务，正在复查状态' : '已创建智能字幕任务，通常十几秒后就绪')
      pollTask(task.taskId)
    } else {
      cloudError.value = '后端未返回 taskId，无法跟踪进度'
    }
  } catch (e) {
    cloudError.value = describeError(e)
  } finally {
    cloudBusy.value = false
  }
}

function pickSrt() {
  srtInput.value?.click()
}

async function onSrtPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  const material = selected.value
  if (!file || !material) return
  srtBusy.value = true
  try {
    const updated = await uploadMaterialSubtitle(material.id, file)
    const index = materials.value.findIndex((item) => item.id === updated.id)
    if (index >= 0) materials.value[index] = updated
    ElMessage.success(`已上传字幕：${updated.srtFileName}`)
  } catch (e) {
    ElMessage.error(describeError(e))
  } finally {
    srtBusy.value = false
  }
}

function onUploaded(material: Material) {
  const index = materials.value.findIndex((item) => item.id === material.id)
  if (index >= 0) materials.value[index] = material
  else materials.value = [material, ...materials.value]
  selectedId.value = material.id
}

async function selectMaterial(id: string) {
  if (selectedId.value === id) return
  selectedId.value = id
  subtitleMode.value = 'none'
  cloudTask.value = null
  cloudError.value = null
  stopPoll()
  await loadExistingTask()
}

function onCaseCreated() {
  manualCreated.value += 1
}

function onImported(count: number) {
  importedCount.value += count
}

function onAiCreated(count: number) {
  aiCreated.value += count
}

function goPrev() {
  if (step.value > 0) step.value -= 1
}

function goNext() {
  if (!canNext.value) return
  if (step.value < STEP_TITLES.length - 1) step.value += 1
}

function finish() {
  const id = selectedId.value
  if (!id) {
    ElMessage.warning('还没有选择素材')
    return
  }
  router.push({ name: 'workbench', query: { material: id } })
}

watch(subtitleMode, (mode) => {
  if (mode !== 'cloud') {
    stopPoll()
    pollAttempt = 0
  }
  if (mode !== 'srt') srtInput.value = null
})

onMounted(load)
onBeforeUnmount(stopPoll)
</script>

<template>
  <div class="lp-page">
    <header class="lp-page-head">
      <div>
        <div class="lp-crumb">
          首页 <span class="sep">/</span> 核验项目 <span class="sep">/</span> 新建
        </div>
        <h1 class="lp-page-title">新建核验项目</h1>
        <p class="lp-page-sub">
          三步走：先确定素材，再准备字幕，最后录入待核验的产品承诺。任何一步都不会自动替你下结论。
        </p>
      </div>
      <div class="lp-page-actions">
        <button type="button" class="lp-btn" @click="router.push({ name: 'tasks' })">返回项目列表</button>
      </div>
    </header>

    <div class="lp-page-body">
      <div v-if="error" class="lp-note warn" style="margin-bottom: 14px">{{ error }}</div>

      <div class="lp-steps">
        <el-steps :active="step" finish-status="success" align-center>
          <el-step v-for="title in STEP_TITLES" :key="title" :title="title" />
        </el-steps>
      </div>

      <!-- 第 1 步：素材 -->
      <div v-if="step === 0" class="lp-wizard-grid">
        <section class="lp-card">
          <div class="lp-card-head">
            <div class="lp-card-title">选择已有素材</div>
            <span class="lp-pill ghost">{{ readyMaterials.length }} 个可用</span>
          </div>
          <div style="padding: 10px 12px 14px; display: flex; flex-direction: column; gap: 8px">
            <!-- 失败整块替换列表：加载失败 ≠ 没有素材 -->
            <LpState
              v-if="error && materials.length === 0"
              variant="error"
              compact
              title="素材列表加载失败"
              :description="error"
            >
              <template #actions>
                <button type="button" class="lp-btn" :disabled="loading" @click="load">
                  {{ loading ? '重试中…' : '重新加载' }}
                </button>
              </template>
            </LpState>
            <LpSkeletonList v-else-if="loading && materials.length === 0" :rows="3" />
            <LpState
              v-else-if="readyMaterials.length === 0"
              compact
              title="还没有可用的素材"
              description="在右侧上传一段 mp4 演示视频，转码就绪后就能选择。"
            />
            <div
              v-for="item in readyMaterials"
              :key="item.id"
              class="lp-material-item"
              :class="{ 'is-active': selectedId === item.id }"
              @click="selectMaterial(item.id)"
            >
              <div class="lp-material-body">
                <div class="lp-material-name">{{ item.originalName ?? item.id }}</div>
                <div class="lp-material-meta">
                  <span class="lp-pill" :class="item.builtin ? 'ghost' : 'mint'">
                    {{ item.builtin ? '内置示例' : '保利威点播' }}
                  </span>
                  <span v-if="item.durationSec">{{ item.durationSec }}s</span>
                  <span v-if="item.width && item.height">{{ item.width }}×{{ item.height }}</span>
                  <span v-if="item.srtFileName">已带 SRT</span>
                  <span v-else>无 SRT</span>
                </div>
              </div>
            </div>
          </div>
        </section>

        <MaterialUploader :capabilities="capabilities" @uploaded="onUploaded" />
      </div>

      <!-- 第 2 步：字幕 -->
      <div v-else-if="step === 1" class="lp-wizard-grid">
        <section class="lp-card">
          <div class="lp-card-head">
            <div class="lp-card-title">准备字幕</div>
            <span class="lp-pill ghost">{{ selected?.originalName ?? selected?.id ?? '未选素材' }}</span>
          </div>
          <div style="padding: 12px 14px; display: flex; flex-direction: column; gap: 10px">
            <div
              class="lp-radio-card"
              :class="{ 'is-active': subtitleMode === 'cloud' }"
              @click="subtitleMode = 'cloud'"
            >
              <div>
                <div class="t">保利威智能字幕</div>
                <div class="d">
                  由保利威云端识别生成，会消耗资源点。创建后通常十几秒就绪；字幕文本与时间码仍须人工核对，
                  智能字幕只提供定位，不作为核验结论。
                </div>
                <div style="margin-top: 8px; display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
                  <span class="lp-pill" :class="cloudReady ? 'mint' : 'ghost'">{{ cloudStatusLabel }}</span>
                  <button
                    type="button"
                    class="lp-btn"
                    style="height: 26px; padding: 0 10px; font-size: 12px"
                    :disabled="cloudBusy || !selectedReady"
                    @click.stop="createCloudTask"
                  >
                    {{
                      cloudBusy
                        ? '处理中…'
                        : cloudReady
                          ? '复用既有任务（不重复消耗）'
                          : cloudTask
                            ? '重新创建 / 复用'
                            : '创建智能字幕任务'
                    }}
                  </button>
                </div>
                <div v-if="cloudError" class="lp-note warn" style="margin-top: 8px">{{ cloudError }}</div>
              </div>
            </div>

            <div
              class="lp-radio-card"
              :class="{ 'is-active': subtitleMode === 'srt' }"
              @click="subtitleMode = 'srt'"
            >
              <div>
                <div class="t">上传本地 SRT 字幕</div>
                <div class="d">
                  适合已有转写稿的场合。SRT 存入本机白名单目录，按素材 id 命名；与视频画面严格对齐后才能用于证据定位。
                </div>
                <div style="margin-top: 8px; display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
                  <span class="lp-pill" :class="selected?.srtFileName ? 'mint' : 'ghost'">
                    {{ selected?.srtFileName ?? '未上传' }}
                  </span>
                  <input ref="srtInput" type="file" accept=".srt" hidden @change="onSrtPicked" />
                  <button
                    type="button"
                    class="lp-btn"
                    style="height: 26px; padding: 0 10px; font-size: 12px"
                    :disabled="srtBusy"
                    @click.stop="pickSrt"
                  >
                    {{ srtBusy ? '上传中…' : '选择 SRT 文件' }}
                  </button>
                </div>
              </div>
            </div>

            <div
              class="lp-radio-card"
              :class="{ 'is-active': subtitleMode === 'none' }"
              @click="subtitleMode = 'none'"
            >
              <div>
                <div class="t">暂不准备字幕</div>
                <div class="d">
                  没有字幕时只能按时间点人工逐段核对画面，无法看到原话文本；工作台会明确标注「无字幕区间」，
                  不会写成「无相关内容」。
                </div>
              </div>
            </div>
          </div>
        </section>

        <section class="lp-card">
          <div class="lp-card-head">
            <div class="lp-card-title">当前状态</div>
          </div>
          <div style="padding: 12px 14px">
            <div class="lp-kv">
              <span class="k">素材</span><span class="v">{{ selected?.originalName ?? '—' }}</span>
              <span class="k">素材 id</span><span class="v">{{ selected?.id ?? '—' }}</span>
              <span class="k">时长</span>
              <span class="v">{{ selected?.durationSec ? `${selected.durationSec}s` : '—' }}</span>
              <span class="k">字幕</span>
              <span class="v">{{ hasSubtitle ? '已就绪' : '尚未准备' }}</span>
            </div>
            <div class="lp-note" style="margin-top: 10px; font-size: 11.5px">
              字幕只用于把承诺定位到画面与文本；它本身不是证据，是否被实际操作证明仍需人工判断。
            </div>
          </div>
        </section>
      </div>

      <!-- 第 3 步：核验事项 -->
      <div v-else class="lp-wizard-grid">
        <section class="lp-card">
          <div class="lp-card-head">
            <div class="lp-card-title">录入待核验的产品承诺</div>
            <span class="lp-pill ghost">{{ caseCountLabel }}</span>
          </div>
          <div style="padding: 12px 14px; display: flex; flex-direction: column; gap: 10px">
            <div class="lp-radio-card" @click="aiDialog = true">
              <div>
                <div class="t">AI 从字幕生成候选</div>
                <div class="d">
                  读取当前素材整段字幕，提取「值得核验的产品承诺」并生成候选清单，可预览、逐条编辑、去重后勾选入库。
                  生成只调一次模型、不写库；入库的事项同样是「尚未核验」，AI 不替你下结论。
                </div>
              </div>
            </div>
            <div class="lp-radio-card" @click="caseDialog = true">
              <div>
                <div class="t">手动新增一条</div>
                <div class="d">
                  逐条录入问题、承诺原文与大致时间区间。新增后的状态是「尚未核验」，回答状态在库里是占位值，
                  不代表视频没有这样说。
                </div>
              </div>
            </div>
            <div class="lp-radio-card" @click="csvDialog = true">
              <div>
                <div class="t">从 CSV 批量导入</div>
                <div class="d">
                  先下载模板，填好后先预览再导入。预览阶段不写库；严格模式下一旦有非法行整批拒绝。
                  批量导入的是你自己整理的问题清单，不是 AI 自动生成的承诺。
                </div>
              </div>
            </div>
            <div class="lp-radio-card" @click="finish">
              <div>
                <div class="t">稍后再加，先进入工作台</div>
                <div class="d">
                  工作台左栏可随时新增或导入；素材与字幕已准备好，不会因为缺少核验事项而丢失。
                </div>
              </div>
            </div>
          </div>
        </section>

        <section class="lp-card">
          <div class="lp-card-head">
            <div class="lp-card-title">即将进入</div>
          </div>
          <div style="padding: 12px 14px">
            <div class="lp-kv">
              <span class="k">素材</span><span class="v">{{ selected?.originalName ?? '—' }}</span>
              <span class="k">字幕</span>
              <span class="v">
                {{ cloudReady ? '保利威智能字幕' : selected?.srtFileName ? '本地 SRT' : '暂无字幕' }}
              </span>
              <span class="k">已录入</span><span class="v">{{ caseCountLabel }}</span>
            </div>
            <div class="lp-note" style="margin-top: 10px; font-size: 11.5px">
              进入工作台后，AI 辅助核验只在手动触发时运行，结果只是建议，需要你逐条采纳并保存。
            </div>
          </div>
        </section>
      </div>

      <div style="display: flex; gap: 8px; justify-content: space-between; margin-top: 16px; align-items: center">
        <button type="button" class="lp-btn" :disabled="step === 0" @click="goPrev">上一步</button>
        <div style="display: flex; gap: 8px; align-items: center">
          <span v-if="nextHint" style="font-size: 11.5px; color: var(--lp-muted)">{{ nextHint }}</span>
          <button v-if="step < 2" type="button" class="lp-btn lp-btn-primary" :disabled="!canNext" @click="goNext">
            下一步
          </button>
          <button v-else type="button" class="lp-btn lp-btn-primary" @click="finish">进入工作台</button>
        </div>
      </div>
    </div>

    <NewCaseDialog
      v-model="caseDialog"
      :material-id="selectedId"
      :materials="materials"
      @created="onCaseCreated"
    />
    <CsvImportDialog
      v-model="csvDialog"
      :material-id="selectedId"
      :materials="materials"
      @imported="(result) => onImported(result.imported)"
    />
    <AiCandidateDialog
      v-model="aiDialog"
      :material-id="selectedId"
      :material="selected"
      @created="onAiCreated"
    />
  </div>
</template>