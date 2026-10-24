<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { SubtitleSource, UnifiedCue } from '@/types/api'
import { formatTime } from '@/constants/meta'
import { usePlayerStore } from '@/stores/player'
import LpSkeletonList from '@/components/LpSkeletonList.vue'
import LpState from '@/components/LpState.vue'
import SegmentedField from '@/components/SegmentedField.vue'

const props = defineProps<{
  cues: UnifiedCue[]
  loading: boolean
  error: string | null
  /** 当前用例在视频中的时间窗，用于「仅当前字幕」过滤 */
  windowStart: number | null
  windowEnd: number | null
  source: SubtitleSource
  /** 云端来源时：是否已有就绪的智能字幕任务 */
  cloudReady: boolean
  subtitleFile: string | null
  /** 当前是否有视频可查字幕：没有选中事项时为 null（用于区分「没选」和「真的没有字幕」） */
  videoName?: string | null
}>()

const emit = defineEmits<{
  (e: 'seek', sec: number): void
  (e: 'switch-source', source: SubtitleSource): void
}>()

const player = usePlayerStore()
const onlyCurrent = ref(false)
const listRef = ref<HTMLElement | null>(null)

const SOURCE_OPTIONS = [
  { value: 'local', label: '本地 SRT' },
  { value: 'cloud', label: '智能字幕' },
]

const sourceValue = computed({
  get: () => props.source,
  set: (value: string) => emit('switch-source', value as SubtitleSource),
})

const sourceHint = computed(() => {
  if (props.source === 'cloud') {
    return props.cloudReady
      ? '保利威智能字幕（云端机器生成，条数与分段与本地 SRT 不同，需人工核对）'
      : '保利威智能字幕尚未就绪：请先在「素材与字幕」页创建或查询任务'
  }
  return props.subtitleFile ? `本地字幕（SRT 解析）· ${props.subtitleFile}` : '本地字幕（SRT 解析）'
})

const visibleCues = computed(() => {
  if (!onlyCurrent.value) return props.cues
  const start = props.windowStart ?? 0
  const end = props.windowEnd ?? Number.POSITIVE_INFINITY
  return props.cues.filter((cue) => cue.endSec > start && cue.startSec < end)
})

/**
 * 空字幕的状态文案。
 *
 * <p>「没有选中事项」与「这个视频真的没有字幕」是两回事，不能都说成后者：
 * 空事项列表下后者会让人以为素材缺字幕，实际只是还没选要核哪一条。
 */
const subtitleEmpty = computed<{ title: string; description: string }>(() => {
  if (!props.videoName) {
    return {
      title: '还没有选中核验事项',
      description: '字幕会随选中的事项加载，先在左侧选一条。',
    }
  }
  if (props.cues.length === 0) {
    return props.source === 'cloud'
      ? {
          title: '云端暂无可显示字幕',
          description: '云端字幕还没有就绪的任务，可在素材库创建或查询。',
        }
      : {
          title: '该视频没有配套字幕',
          description: '没有找到这个视频的 SRT 字幕文件，可在素材库上传。',
        }
  }
  return {
    title: '时间窗内没有字幕',
    description: '当前问题的时间范围内没有字幕句子，可点上方开关查看全片字幕。',
  }
})

const currentIndex = computed(() => {
  const t = player.currentTime
  return props.cues.findIndex((cue) => t >= cue.startSec && t <= cue.endSec)
})

watch(currentIndex, (index) => {
  if (index < 0 || !listRef.value) return
  const node = listRef.value.querySelector<HTMLElement>(`[data-cue-index="${index}"]`)
  node?.scrollIntoView({ block: 'nearest' })
})

function jump(cue: UnifiedCue) {
  emit('seek', cue.startSec + 0.05)
}
</script>

<template>
  <section class="lp-card" style="flex: 1 1 auto; min-height: 0">
    <div class="lp-card-head">
      <div class="lp-card-title">
        字幕记录
        <span class="lp-pill" :class="source === 'cloud' ? 'ink' : 'mint'">
          {{ source === 'cloud' ? '智能字幕' : '本地字幕' }}
        </span>
      </div>
      <div style="display: flex; align-items: center; gap: 10px">
        <SegmentedField v-model="sourceValue" :options="SOURCE_OPTIONS" :disabled="loading" />
        <span style="font-size: 11.5px; color: var(--lp-muted)">共 {{ cues.length }} 条</span>
        <el-switch v-model="onlyCurrent" size="small" />
        <span style="font-size: 11.5px; color: var(--lp-muted)">仅当前字幕</span>
      </div>
    </div>

    <div style="padding: 6px 12px 0; font-size: 11.5px; color: var(--lp-muted-soft)">
      {{ sourceHint }}
    </div>

    <div ref="listRef" class="lp-scroll" style="flex: 1 1 auto; padding: 6px 8px 10px">
      <div v-if="error" class="lp-note warn" style="margin: 4px">{{ error }}</div>
      <LpSkeletonList v-else-if="loading" :rows="4" />
      <LpState
        v-else-if="visibleCues.length === 0"
        compact
        :title="subtitleEmpty.title"
        :description="subtitleEmpty.description"
      />
      <div
        v-for="cue in visibleCues"
        :key="`${cue.source}-${cue.index}`"
        class="lp-cue"
        :class="{ 'is-current': currentIndex >= 0 && cues[currentIndex]?.index === cue.index }"
        :data-cue-index="cue.index"
        @click="jump(cue)"
      >
        <span class="lp-cue-time">{{ formatTime(cue.startSec) }}</span>
        <span class="lp-cue-text">{{ cue.text }}</span>
      </div>
    </div>
  </section>
</template>
