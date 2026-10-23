import { defineStore } from 'pinia'
import { shallowRef, ref } from 'vue'

/**
 * 播放器控制：跨组件共享同一个 <video> 元素。
 * 字幕点击、时间点点击、切换用例都通过 seekTo 驱动真实播放位置。
 */
export const usePlayerStore = defineStore('player', () => {
  const videoEl = shallowRef<HTMLVideoElement | null>(null)
  const currentTime = ref(0)
  const duration = ref(0)
  const paused = ref(true)
  const pendingSeek = ref<{ sec: number; nonce: number } | null>(null)

  function attach(el: HTMLVideoElement | null) {
    videoEl.value = el
    if (el) {
      currentTime.value = el.currentTime
      duration.value = Number.isFinite(el.duration) ? el.duration : 0
    }
  }

  function seekTo(sec: number, autoplay = false) {
    const el = videoEl.value
    const target = Math.max(0, sec)
    if (el) {
      el.currentTime = target
      currentTime.value = target
      if (autoplay) {
        void el.play().catch(() => {
          /* 浏览器可能因无用户手势拒绝自动播放，忽略即可 */
        })
      }
    }
    // 元素尚未挂载时留一个待处理请求，挂载后执行
    pendingSeek.value = { sec: target, nonce: Date.now() }
  }

  function consumePending() {
    const pending = pendingSeek.value
    pendingSeek.value = null
    return pending
  }

  function clear() {
    pendingSeek.value = null
  }

  return { videoEl, currentTime, duration, paused, pendingSeek, attach, seekTo, consumePending, clear }
})
