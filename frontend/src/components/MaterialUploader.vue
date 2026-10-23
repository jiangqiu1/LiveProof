<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchMaterial, uploadMaterial } from '@/api'
import { describeError } from '@/api/client'
import type { Material, MaterialCapabilities } from '@/types/api'

const props = defineProps<{
  capabilities: MaterialCapabilities | null
}>()

const emit = defineEmits<{
  (e: 'uploaded', material: Material): void
}>()

/** 上传阶段：idle → uploading（真实进度）→ transcoding（保利威转码）→ done / failed */
type Phase = 'idle' | 'uploading' | 'transcoding' | 'done' | 'failed'

const phase = ref<Phase>('idle')
const percent = ref(0)
const error = ref<string | null>(null)
const result = ref<Material | null>(null)
const fileName = ref<string | null>(null)
const fileSize = ref(0)
const dragOver = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

let pollTimer: number | null = null
let pollAttempt = 0

const MAX_POLL_ATTEMPTS = 60
const POLL_INTERVAL_MS = 3000

const busy = computed(() => phase.value === 'uploading' || phase.value === 'transcoding')
const uploadEnabled = computed(() => Boolean(props.capabilities?.uploadEnabled))

const maxBytesLabel = computed(() => {
  const max = props.capabilities?.maxBytes
  if (!max) return '≤200MB'
  return `≤${Math.round(max / (1024 * 1024))}MB`
})

const phaseLabel = computed(() => {
  switch (phase.value) {
    case 'uploading':
      return `正在上传到保利威点播 ${percent.value}%`
    case 'transcoding':
      return '保利威正在转码，通常 1 分钟内完成'
    case 'done':
      return '素材已就绪'
    case 'failed':
      return '上传或转码失败'
    default:
      return ''
  }
})

function formatSize(bytes: number): string {
  if (bytes <= 0) return '—'
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
}

function stopPoll() {
  if (pollTimer !== null) {
    window.clearTimeout(pollTimer)
    pollTimer = null
  }
}

/** 轮询转码状态：只在后端返回真实状态，不伪造进度百分比。 */
function pollStatus(id: string) {
  stopPoll()
  pollAttempt += 1
  if (pollAttempt > MAX_POLL_ATTEMPTS) {
    phase.value = 'failed'
    error.value = '转码等待超时（已轮询 3 分钟仍未就绪），可在素材列表中稍后刷新查看'
    return
  }
  pollTimer = window.setTimeout(async () => {
    try {
      const material = await fetchMaterial(id)
      result.value = material
      if (material.status === 'ready') {
        phase.value = 'done'
        ElMessage.success('素材已就绪，可以准备字幕并新增核验事项')
        emit('uploaded', material)
        return
      }
      if (material.status === 'failed') {
        phase.value = 'failed'
        error.value = '保利威转码失败，请检查视频格式后重新上传'
        return
      }
      pollStatus(id)
    } catch (e) {
      phase.value = 'failed'
      error.value = describeError(e)
    }
  }, POLL_INTERVAL_MS)
}

async function submit(file: File) {
  if (!uploadEnabled.value) {
    error.value = '后端未配置保利威点播上传凭据（POLYV_VOD_WRITE_TOKEN），上传接口不可用'
    phase.value = 'failed'
    return
  }
  const max = props.capabilities?.maxBytes ?? 0
  if (max > 0 && file.size > max) {
    error.value = `文件 ${formatSize(file.size)} 超过上限 ${maxBytesLabel.value}`
    phase.value = 'failed'
    return
  }
  if (!/\.mp4$/i.test(file.name)) {
    error.value = '只支持 mp4 格式'
    phase.value = 'failed'
    return
  }

  stopPoll()
  pollAttempt = 0
  phase.value = 'uploading'
  percent.value = 0
  error.value = null
  result.value = null
  fileName.value = file.name
  fileSize.value = file.size

  try {
    const created = await uploadMaterial(file, (value) => {
      percent.value = value
    })
    result.value = created
    percent.value = 100
    if (created.status === 'ready') {
      phase.value = 'done'
      emit('uploaded', created)
      return
    }
    phase.value = 'transcoding'
    pollStatus(created.id)
  } catch (e) {
    phase.value = 'failed'
    error.value = describeError(e)
  }
}

function onPick(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) void submit(file)
  input.value = ''
}

function onDrop(event: DragEvent) {
  dragOver.value = false
  const file = event.dataTransfer?.files?.[0]
  if (file) void submit(file)
}

function reset() {
  stopPoll()
  phase.value = 'idle'
  percent.value = 0
  error.value = null
  result.value = null
  fileName.value = null
  fileSize.value = 0
}

onBeforeUnmount(stopPoll)
</script>

<template>
  <section class="lp-card">
    <div class="lp-card-head">
      <div class="lp-card-title">上传视频素材</div>
      <span class="lp-pill" :class="uploadEnabled ? 'mint' : 'warn'">
        {{ uploadEnabled ? '上传通道就绪' : '上传未配置' }}
      </span>
    </div>

    <div style="padding: 12px 14px; display: flex; flex-direction: column; gap: 10px">
      <div
        class="lp-upload-zone"
        :class="{ 'is-over': dragOver, 'is-busy': busy }"
        @dragover.prevent="dragOver = true"
        @dragleave.prevent="dragOver = false"
        @drop.prevent="onDrop"
        @click="!busy && fileInput?.click()"
      >
        <input ref="fileInput" type="file" accept="video/mp4,.mp4" hidden @change="onPick" />
        <div class="lp-upload-title">
          {{ busy ? phaseLabel : '点击或拖拽 mp4 文件到此处' }}
        </div>
        <div class="lp-upload-desc">
          视频将上传至保利威点播，用于生成字幕与画面证据；支持 mp4，{{ maxBytesLabel }}。
        </div>
        <div v-if="busy" class="lp-progress">
          <div class="lp-progress-bar" :style="{ width: `${phase === 'uploading' ? percent : 100}%` }"></div>
        </div>
        <div v-if="busy" class="lp-upload-desc" style="margin-top: 6px">
          <template v-if="phase === 'uploading'">
            {{ fileName }} · {{ formatSize(fileSize) }} · {{ percent }}%
          </template>
          <template v-else>已上传完成，等待保利威转码（不编造进度百分比）</template>
        </div>
      </div>

      <div v-if="error" class="lp-note warn">{{ error }}</div>

      <div v-if="result" class="lp-kv">
        <span class="k">素材 id</span><span class="v">{{ result.id }}</span>
        <span class="k">原始文件名</span><span class="v">{{ result.originalName ?? '—' }}</span>
        <span class="k">状态</span><span class="v">{{ result.status }}</span>
        <span class="k">时长</span>
        <span class="v">{{ result.durationSec ? `${result.durationSec}s` : '—' }}</span>
        <span class="k">分辨率</span>
        <span class="v">
          {{ result.width && result.height ? `${result.width}×${result.height}` : '—' }}
        </span>
        <span class="k">发布状态</span>
        <span class="v">{{ result.published ? '已发布' : '等待审核（playUrl 已可播）' }}</span>
      </div>

      <div style="display: flex; gap: 8px">
        <button
          type="button"
          class="lp-btn"
          style="height: 26px; padding: 0 10px; font-size: 12px"
          :disabled="busy"
          @click="reset"
        >
          清除状态
        </button>
        <span style="font-size: 11.5px; color: var(--lp-muted); align-self: center">
          素材上限 {{ capabilities?.maxCount ?? 20 }} 个
        </span>
      </div>
    </div>
  </section>
</template>