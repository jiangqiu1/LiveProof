<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { usePlayerStore } from '@/stores/player'

const props = defineProps<{
  /** 媒体文件名；由后端 /media/{name} 白名单放行（内置素材轨道） */
  src: string | null
  /**
   * 保利威 CDN 直链（云端素材轨道）。
   * 有值则直接使用，忽略 /media 路径；两条轨道互不替代。
   */
  playUrl?: string | null
}>()

const player = usePlayerStore()
const videoRef = ref<HTMLVideoElement | null>(null)
const mediaError = ref<string | null>(null)
const ready = ref(false)

const mediaUrl = computed(() => {
  if (props.playUrl) return props.playUrl
  return props.src ? `/media/${encodeURIComponent(props.src)}` : ''
})

/** 提示里显示「来源」，避免云端素材在画面上被说成本地文件 */
const sourceHint = computed(() => (props.playUrl ? '保利威点播' : '本地素材'))

function syncTime() {
  const el = videoRef.value
  if (!el) return
  player.currentTime = el.currentTime
  player.paused = el.paused
}

function onLoadedMetadata() {
  const el = videoRef.value
  if (!el) return
  ready.value = true
  mediaError.value = null
  player.duration = Number.isFinite(el.duration) ? el.duration : 0
  syncTime()
}

function onError() {
  ready.value = false
  mediaError.value = props.playUrl
    ? '视频加载失败：保利威播放地址不可用或已过期，请重新取地址'
    : props.src
      ? `视频加载失败：/media/${props.src}（后端白名单或文件缺失）`
      : '未选择视频'
}

onMounted(() => {
  player.attach(videoRef.value)
  if (videoRef.value) {
    videoRef.value.addEventListener('timeupdate', syncTime)
    videoRef.value.addEventListener('seeked', syncTime)
    videoRef.value.addEventListener('play', syncTime)
    videoRef.value.addEventListener('pause', syncTime)
  }
})

onBeforeUnmount(() => {
  player.attach(null)
})

watch(
  () => [props.src, props.playUrl],
  () => {
    ready.value = false
    mediaError.value = null
    void videoRef.value?.load()
  },
)

// 字幕 / 时间点点击后跳转到真实播放位置
watch(
  () => player.pendingSeek?.nonce,
  () => {
    const pending = player.consumePending()
    const el = videoRef.value
    if (!pending || !el) return
    el.currentTime = pending.sec
    syncTime()
  },
)
</script>

<template>
  <div class="lp-video-wrap">
    <video
      ref="videoRef"
      class="lp-video"
      :src="mediaUrl"
      controls
      preload="metadata"
      playsinline
      @loadedmetadata="onLoadedMetadata"
      @error="onError"
    ></video>
    <div class="lp-video-hint">
      <span>
        <template v-if="mediaError">{{ mediaError }}</template>
        <template v-else-if="ready">
          {{ playUrl ? '保利威点播' : src }} · 时长 {{ player.duration.toFixed(1) }}s · 支持拖动
        </template>
        <template v-else>正在加载视频（来源：{{ sourceHint }}）…</template>
      </span>
    </div>
  </div>
</template>