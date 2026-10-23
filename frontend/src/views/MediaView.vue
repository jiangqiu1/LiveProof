<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createSmartSubtitleTask,
  deleteMaterial,
  deleteMaterialSubtitle,
  fetchMaterialCapabilities,
  fetchMaterialPlay,
  fetchMaterials,
  fetchSmartSubtitleStatus,
  fetchSmartSubtitleTasks,
  fetchSubtitles,
  refreshSmartSubtitleTask,
  uploadMaterialSubtitle,
} from '@/api'
import { describeError } from '@/api/client'
import type {
  Material,
  MaterialCapabilities,
  SmartSubtitleStatusInfo,
  SmartSubtitleTask,
} from '@/types/api'
import VideoPlayer from '@/components/VideoPlayer.vue'
import MaterialUploader from '@/components/MaterialUploader.vue'
import { formatDateTime, formatTime } from '@/constants/meta'
import { usePlayerStore } from '@/stores/player'

const player = usePlayerStore()

const materials = ref<Material[]>([])
const capabilities = ref<MaterialCapabilities | null>(null)
const currentId = ref<string | null>(null)
const materialsLoading = ref(false)
const materialsError = ref<string | null>(null)

/** 云端播放地址：只有 polyv 素材才取 */
const playUrl = ref<string | null>(null)
const playError = ref<string | null>(null)
const playLoading = ref(false)

/** 本地字幕状态（沿用既有 /api/subtitles 口径，文案不改） */
const localSubtitleInfo = ref<{ cueCount: number; subtitleFile: string } | null>(null)
const localSubtitleError = ref<string | null>(null)

/* ---------- 云端智能字幕（保利威） ---------- */
const cloudStatus = ref<SmartSubtitleStatusInfo | null>(null)
const cloudTask = ref<SmartSubtitleTask | null>(null)
const cloudError = ref<string | null>(null)
const cloudBusy = ref(false)
const cloudLoading = ref(false)

const srtInput = ref<HTMLInputElement | null>(null)
const srtBusy = ref(false)

const STATUS_LABEL: Record<string, string> = {
  created: '已创建，待查询',
  processing: '生成中',
  ready: '已就绪',
  failed: '生成失败',
  not_found: '云端不存在',
}

const MATERIAL_STATUS_LABEL: Record<string, string> = {
  transcoding: '保利威转码中',
  ready: '已就绪',
  failed: '转码失败',
  deleted: '已删除',
}

const current = computed(() => materials.value.find((item) => item.id === currentId.value) ?? null)

/** 云端字幕任务按素材 id 或文件名匹配（同一 VID 的登记行后端会一并返回） */
const cloudVideoKey = computed(() => current.value?.fileName ?? current.value?.id ?? null)

const cloudVidConfigured = computed(() => {
  const key = cloudVideoKey.value
  if (!key || !cloudStatus.value) return false
  return Boolean(cloudStatus.value.videos.find((item) => item.video === key)?.vidConfigured)
})

const cloudStatusLabel = computed(() => {
  if (!cloudStatus.value) return '未检测'
  if (!cloudStatus.value.configured) return '未配置凭据'
  if (!cloudVidConfigured.value) return '该素材未映射 VID'
  return '可创建任务'
})

const cloudReady = computed(() => cloudTask.value?.status === 'ready')
const materialReady = computed(() => current.value?.status === 'ready')

async function loadMaterials(keepSelection = true) {
  materialsLoading.value = true
  materialsError.value = null
  const previous = currentId.value
  try {
    const [list, caps] = await Promise.all([fetchMaterials(), fetchMaterialCapabilities()])
    materials.value = list
    capabilities.value = caps
    if (!keepSelection || !previous || !list.some((item) => item.id === previous)) {
      currentId.value = list[0]?.id ?? null
    }
  } catch (error) {
    materialsError.value = describeError(error)
  } finally {
    materialsLoading.value = false
  }
}

/** 解析当前素材的播放源与字幕状态 */
async function loadCurrent() {
  const material = current.value
  player.clear()
  player.seekTo(0)
  playUrl.value = null
  playError.value = null
  localSubtitleInfo.value = null
  localSubtitleError.value = null
  cloudTask.value = null
  cloudError.value = null
  if (!material) return

  if (material.storage === 'polyv') {
    playLoading.value = true
    try {
      const play = await fetchMaterialPlay(material.id)
      playUrl.value = play.playUrl
    } catch (error) {
      playError.value = describeError(error)
    } finally {
      playLoading.value = false
    }
  }

  await loadLocalSubtitle(material)
  void loadCloudStatus()
}

/**
 * 读取素材登记的字幕并显示真实条数。
 *
 * <p>只有登记了 SRT 才查，避免无意义请求；查失败如实显示，不假装「已上传」。
 * 用文件名而不是素材 id 查询：工作台与 AI 核验也是用同一个标识取字幕，
 * 两边口径必须一致，否则会出现「素材页显示已上传、核验时拿不到」。
 */
async function loadLocalSubtitle(material: Material | null) {
  localSubtitleInfo.value = null
  localSubtitleError.value = null
  if (!material?.srtFileName) return
  try {
    const local = await fetchSubtitles(material.fileName ?? material.id)
    localSubtitleInfo.value = { cueCount: local.cueCount, subtitleFile: local.subtitleFile }
  } catch (error) {
    localSubtitleInfo.value = null
    localSubtitleError.value = describeError(error)
  }
}

async function loadCloudStatus() {
  const key = cloudVideoKey.value
  if (!key) return
  cloudLoading.value = true
  cloudError.value = null
  try {
    const [status, tasks] = await Promise.all([
      fetchSmartSubtitleStatus(),
      fetchSmartSubtitleTasks(key),
    ])
    cloudStatus.value = status
    // 同一素材可能有多条登记（例如先失败后成功）：优先展示已就绪的那条，
    // 否则「已就绪」会被一条旧的失败登记盖住，用户以为还要再创建一次
    cloudTask.value = tasks.find((task) => task.status === 'ready') ?? tasks[0] ?? null
  } catch (error) {
    cloudError.value = describeError(error)
  } finally {
    cloudLoading.value = false
  }
}

async function createOrReuse() {
  const key = cloudVideoKey.value
  if (!key || cloudBusy.value) return
  cloudBusy.value = true
  cloudError.value = null
  try {
    const task = await createSmartSubtitleTask(key)
    cloudTask.value = task
    ElMessage.success(
      task.reused
        ? `已复用既有智能字幕任务（taskId ${task.taskId}），未重复消耗资源点`
        : `已创建智能字幕任务（taskId ${task.taskId}），通常十几秒后可取`,
    )
  } catch (error) {
    cloudError.value = describeError(error)
    ElMessage.error('创建智能字幕任务失败，详情见下方提示')
  } finally {
    cloudBusy.value = false
  }
}

async function refreshTask() {
  const task = cloudTask.value
  if (!task?.taskId || cloudBusy.value) return
  cloudBusy.value = true
  cloudError.value = null
  try {
    const updated = await refreshSmartSubtitleTask(task.taskId)
    cloudTask.value = updated
    ElMessage.success(`已复查云端任务：${STATUS_LABEL[updated.status] ?? updated.status}`)
  } catch (error) {
    cloudError.value = describeError(error)
  } finally {
    cloudBusy.value = false
  }
}

async function selectMaterial(id: string) {
  if (currentId.value === id) return
  currentId.value = id
  await loadCurrent()
}

async function removeCurrent() {
  const material = current.value
  if (!material || material.builtin) {
    ElMessage.warning('内置示例素材受保护，不能删除')
    return
  }
  try {
    await ElMessageBox.confirm(
      `删除素材「${material.originalName ?? material.id}」会一并移除它的本地字幕文件；已归属它的事项仍保留，但会失去可播放的素材。确认删除？`,
      '删除素材',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await deleteMaterial(material.id)
    ElMessage.success('素材已删除')
    currentId.value = null
    await loadMaterials(false)
    await loadCurrent()
  } catch (error) {
    ElMessage.error(describeError(error))
  }
}

function pickSrt() {
  srtInput.value?.click()
}

async function onSrtPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  const material = current.value
  if (!file || !material) return
  srtBusy.value = true
  try {
    const updated = await uploadMaterialSubtitle(material.id, file)
    const index = materials.value.findIndex((item) => item.id === updated.id)
    if (index >= 0) materials.value[index] = updated
    // 条数必须真的去解析一次：写死 0 会让「已上传」看起来像是没生效
    await loadLocalSubtitle(updated)
    ElMessage.success(
      localSubtitleError.value
        ? `已上传字幕：${updated.srtFileName}（但读取失败：${localSubtitleError.value}）`
        : `已上传字幕：${updated.srtFileName} · ${localSubtitleInfo.value?.cueCount ?? 0} 条`,
    )
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    srtBusy.value = false
  }
}

async function removeSrt() {
  const material = current.value
  if (!material) return
  srtBusy.value = true
  try {
    const updated = await deleteMaterialSubtitle(material.id)
    const index = materials.value.findIndex((item) => item.id === updated.id)
    if (index >= 0) materials.value[index] = updated
    localSubtitleInfo.value = null
    ElMessage.success('本地字幕已移除')
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    srtBusy.value = false
  }
}

async function onUploaded(material: Material) {
  await loadMaterials(true)
  currentId.value = material.id
  await loadCurrent()
}

function formatSize(bytes: number | null): string {
  if (!bytes || bytes <= 0) return '—'
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
}

onMounted(async () => {
  await loadMaterials(false)
  await loadCurrent()
  void loadMaterials(true)
})
</script>

<template>
  <div class="lp-page">
    <header class="lp-page-head">
      <div>
        <div class="lp-crumb">工作台 <span class="sep">/</span> 视频素材</div>
        <h1 class="lp-page-title">视频素材</h1>
        <p class="lp-page-sub">
          内置示例走本地 /media 白名单；新上传的视频存入保利威点播，播放与截图均经后端代理，页面不接触路径与密钥。
        </p>
      </div>
      <div class="lp-page-actions">
        <span class="lp-pill" :class="capabilities?.uploadEnabled ? 'mint' : 'warn'">
          {{ capabilities?.uploadEnabled ? '保利威点播已接入' : '保利威点播未配置' }}
        </span>
      </div>
    </header>

    <div class="lp-page-body">
      <div
        style="display: grid; grid-template-columns: 300px minmax(0, 1fr) 320px; gap: 14px; align-items: start"
      >
        <!-- 左：素材列表 -->
        <section class="lp-card">
          <div class="lp-card-head">
            <div class="lp-card-title">素材库</div>
            <span class="lp-pill ghost">{{ materials.length }} 个</span>
          </div>
          <div
            class="lp-scroll"
            style="max-height: 560px; padding: 8px 10px 12px; display: flex; flex-direction: column; gap: 8px"
          >
            <div v-if="materialsError" class="lp-note warn">{{ materialsError }}</div>
            <div v-else-if="materialsLoading && materials.length === 0" class="lp-empty">
              正在加载素材…
            </div>
            <div v-else-if="materials.length === 0" class="lp-empty">还没有素材</div>
            <div
              v-for="item in materials"
              :key="item.id"
              class="lp-material-item"
              :class="{ 'is-active': item.id === currentId }"
              @click="selectMaterial(item.id)"
            >
              <div class="lp-material-body">
                <div class="lp-material-name">{{ item.originalName ?? item.id }}</div>
                <div class="lp-material-meta">
                  <span class="lp-pill" :class="item.builtin ? 'ghost' : 'mint'">
                    {{ item.builtin ? '内置' : '保利威' }}
                  </span>
                  <span class="lp-pill" :class="item.status === 'ready' ? 'mint' : item.status === 'failed' ? 'warn' : 'ink'">
                    {{ MATERIAL_STATUS_LABEL[item.status] ?? item.status }}
                  </span>
                  <span v-if="item.durationSec">{{ item.durationSec }}s</span>
                  <span v-if="item.srtFileName">SRT</span>
                </div>
              </div>
            </div>
          </div>
        </section>

        <!-- 中：预览 + 状态 -->
        <div style="display: flex; flex-direction: column; gap: 14px; min-width: 0">
          <section class="lp-card">
            <div class="lp-card-head">
              <div class="lp-card-title">素材预览</div>
              <span class="lp-pill ghost">
                {{ current?.storage === 'polyv' ? '保利威点播' : '本地 /media' }}
              </span>
            </div>
            <VideoPlayer
              v-if="current"
              :key="current.id"
              :src="current.storage === 'local' ? current.fileName : null"
              :play-url="current.storage === 'polyv' ? playUrl : null"
            />
            <div v-else-if="playLoading" class="lp-empty">正在取保利威播放地址…</div>
            <div v-else class="lp-empty">请选择左侧素材</div>
            <div style="padding: 0 14px 14px; font-size: 12px; color: var(--lp-muted)">
              当前播放位置 {{ formatTime(player.currentTime) }} / {{ formatTime(player.duration) }}，可在播放器中拖动。
            </div>
            <div v-if="playError" class="lp-note warn" style="margin: 0 14px 14px">
              {{ playError }}
            </div>
          </section>

          <section class="lp-card">
            <div class="lp-card-head">
              <div class="lp-card-title">素材与字幕状态</div>
              <span v-if="current" class="lp-pill ghost">{{ current.id }}</span>
            </div>
            <div style="padding: 12px 14px">
              <div v-if="!current" class="lp-empty">未选择素材</div>
              <div v-else class="lp-kv">
                <span class="k">原始文件名</span><span class="v">{{ current.originalName ?? '—' }}</span>
                <span class="k">素材来源</span>
                <span class="v">{{ current.storage === 'polyv' ? '保利威点播（云端转码）' : '内置示例（本地白名单）' }}</span>
                <span class="k">播放地址</span>
                <span class="v">
                  {{ current.storage === 'polyv' ? (playUrl ? '保利威 CDN 直链（已取到）' : '未取到') : `/media/${current.fileName}` }}
                </span>
                <span class="k">状态</span>
                <span class="v">{{ MATERIAL_STATUS_LABEL[current.status] ?? current.status }}</span>
                <span class="k">时长</span>
                <span class="v">{{ current.durationSec ? `${current.durationSec} 秒` : '—' }}</span>
                <span class="k">分辨率</span>
                <span class="v">{{ current.width && current.height ? `${current.width}×${current.height}` : '—' }}</span>
                <span class="k">文件大小</span><span class="v">{{ formatSize(current.sizeBytes) }}</span>
                <span class="k">发布状态</span>
                <span class="v">
                  {{ current.builtin ? '不适用（本地素材）' : current.published ? '已发布' : '等待审核（playUrl 已可播）' }}
                </span>
                <span class="k">本地字幕</span>
                <span class="v">
                  {{ current.srtFileName ?? '未上传' }}
                  <template v-if="localSubtitleInfo"> · {{ localSubtitleInfo.cueCount }} 条</template>
                </span>
                <span class="k">创建时间</span><span class="v">{{ formatDateTime(current.createdAt) }}</span>
              </div>

              <div v-if="current && !materialReady && current.status === 'transcoding'" class="lp-note warn" style="margin-top: 10px">
                保利威正在转码，通常 1 分钟内完成；转码完成前不能新增核验事项。
              </div>

              <div style="display: flex; gap: 8px; margin-top: 12px; flex-wrap: wrap">
                <input ref="srtInput" type="file" accept=".srt" hidden @change="onSrtPicked" />
                <button
                  type="button"
                  class="lp-btn"
                  style="height: 26px; padding: 0 10px; font-size: 12px"
                  :disabled="!current || srtBusy"
                  @click="pickSrt"
                >
                  {{ srtBusy ? '处理中…' : '上传 SRT 字幕' }}
                </button>
                <button
                  type="button"
                  class="lp-btn"
                  style="height: 26px; padding: 0 10px; font-size: 12px"
                  :disabled="!current?.srtFileName || srtBusy"
                  @click="removeSrt"
                >
                  移除 SRT
                </button>
                <button
                  type="button"
                  class="lp-btn"
                  style="height: 26px; padding: 0 10px; font-size: 12px; color: var(--lp-danger)"
                  :disabled="!current || current.builtin"
                  @click="removeCurrent"
                >
                  删除素材
                </button>
              </div>
              <div v-if="localSubtitleError" class="lp-note warn" style="margin-top: 8px">
                {{ localSubtitleError }}
              </div>
            </div>
          </section>
        </div>

        <!-- 右：上传 + 云端字幕 -->
        <div style="display: flex; flex-direction: column; gap: 14px">
          <MaterialUploader :capabilities="capabilities" @uploaded="onUploaded" />

          <section class="lp-card">
            <div class="lp-card-head">
              <div class="lp-card-title">字幕来源</div>
              <span class="lp-pill" :class="cloudStatus?.configured ? 'mint' : 'warn'">
                {{ cloudStatusLabel }}
              </span>
            </div>
            <div style="padding: 12px 14px; display: flex; flex-direction: column; gap: 10px">
              <div>
                <div style="display: flex; align-items: center; gap: 6px">
                  <span class="lp-pill" :class="current?.srtFileName ? 'mint' : 'ghost'">
                    {{ current?.srtFileName ? '已有 SRT' : '无 SRT' }}
                  </span>
                  <span style="font-size: 12.5px; font-weight: 600">本地 SRT 字幕</span>
                </div>
                <div style="font-size: 11.5px; color: var(--lp-muted); margin-top: 4px">
                  使用本地 SRT 文件解析，不调用任何语音识别服务；本地字幕与云端字幕的时间戳都以视频时间为准。
                </div>
              </div>

              <div class="lp-divider"></div>

              <div>
                <div style="display: flex; align-items: center; gap: 6px">
                  <span class="lp-pill" :class="cloudReady ? 'mint' : 'ink'">
                    {{ cloudReady ? '已就绪' : '云端' }}
                  </span>
                  <span style="font-size: 12.5px; font-weight: 600">保利威智能字幕</span>
                </div>
                <div style="font-size: 11.5px; color: var(--lp-muted); margin-top: 4px">
                  通过后端登记并复用云端任务，同一视频不会重复创建（不重复消耗资源点）。
                  云端字幕为机器生成，条数与分段与本地 SRT 不同，可作为第二种字幕来源交叉核对。
                </div>

                <div class="lp-kv" style="margin-top: 8px">
                  <span class="k">接入状态</span>
                  <span class="v">{{ cloudStatus?.note ?? '正在读取接入状态…' }}</span>
                  <span class="k">服务地址</span>
                  <span class="v">{{ cloudStatus?.baseUrlHost ?? '—' }}</span>
                  <span class="k">任务 ID</span>
                  <span class="v">{{ cloudTask?.taskId ?? '尚未创建' }}</span>
                  <span class="k">任务状态</span>
                  <span class="v">
                    {{ cloudTask ? (STATUS_LABEL[cloudTask.status] ?? cloudTask.status) : '—' }}
                  </span>
                  <span class="k">字幕条数</span>
                  <span class="v">{{ cloudTask?.subtitleCount ?? '—' }}</span>
                  <span class="k">最近成功</span>
                  <span class="v">{{ cloudTask?.lastSuccessAt ?? '—' }}</span>
                </div>

                <div
                  v-if="cloudTask?.errorMessage"
                  class="lp-note warn"
                  style="margin-top: 8px; font-size: 11.5px"
                >
                  最近一次失败：{{ cloudTask.errorCode }} · {{ cloudTask.errorMessage }}
                </div>

                <div style="display: flex; gap: 8px; margin-top: 10px">
                  <button
                    type="button"
                    class="lp-btn lp-btn-primary"
                    style="height: 26px; padding: 0 10px; font-size: 12px"
                    :disabled="cloudBusy || !cloudStatus?.configured || !cloudVidConfigured || !materialReady"
                    @click="createOrReuse"
                  >
                    {{ cloudBusy ? '处理中…' : cloudTask ? '复用 / 创建任务' : '创建智能字幕任务' }}
                  </button>
                  <button
                    type="button"
                    class="lp-btn"
                    style="height: 26px; padding: 0 10px; font-size: 12px"
                    :disabled="cloudBusy || !cloudTask?.taskId"
                    @click="refreshTask"
                  >
                    复查云端状态
                  </button>
                </div>

                <div v-if="cloudLoading" class="lp-note" style="margin-top: 8px">正在读取云端状态…</div>
                <div v-else-if="cloudError" class="lp-note warn" style="margin-top: 8px">
                  {{ cloudError }}
                </div>
                <div
                  v-else-if="current && !materialReady"
                  class="lp-note warn"
                  style="margin-top: 8px; font-size: 11.5px"
                >
                  素材尚未就绪，不能创建智能字幕任务。
                </div>
                <div
                  v-else-if="!cloudVidConfigured && cloudStatus?.configured"
                  class="lp-note warn"
                  style="margin-top: 8px; font-size: 11.5px"
                >
                  当前素材没有配置保利威 VID，后端拒绝创建任务（不猜测目标视频）。
                </div>
              </div>
            </div>
          </section>
        </div>
      </div>
    </div>
  </div>
</template>