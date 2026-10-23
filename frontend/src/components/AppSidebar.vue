<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import logoUrl from '@/assets/logo-liveproof.png'
import { useWorkbenchStore } from '@/stores/workbench'

const route = useRoute()
const store = useWorkbenchStore()

/** 侧栏底部的素材说明必须跟着当前字幕来源，否则云端来源下会说错话 */
const sourceFoot = computed(() =>
  store.subtitleSource === 'cloud'
    ? { title: '保利威智能字幕', desc: '当前字幕来自云端任务，仍须人工核对' }
    : { title: '本地演示素材', desc: '当前使用本地视频与 SRT 字幕' },
)

const items = [
  { name: 'workbench', label: '工作台', to: '/' },
  { name: 'tasks', label: '核验任务', to: '/tasks' },
  { name: 'media', label: '视频素材', to: '/materials' },
  { name: 'records', label: '核验记录', to: '/records' },
  { name: 'todos', label: '待办复盘', to: '/todos' },
] as const

/** new-task 是核验任务的子页面，导航高亮要归到父项，否则侧栏会全暗 */
const activeName = computed(() => {
  const name = (route.name as string) ?? 'workbench'
  return name === 'new-task' ? 'tasks' : name
})
</script>

<template>
  <aside class="lp-sidebar">
    <div class="lp-brand">
      <img :src="logoUrl" alt="LiveProof 视频演示证据核验" />
    </div>

    <nav class="lp-nav">
      <RouterLink v-for="item in items" :key="item.name" :to="item.to" custom>
        <template #default="{ navigate, isExactActive }">
          <button
            type="button"
            class="lp-nav-item"
            :class="{ 'is-active': isExactActive || activeName === item.name }"
            @click="navigate"
          >
            <svg
              v-if="item.name === 'workbench'"
              width="18"
              height="18"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.7"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <rect x="3" y="3" width="7" height="7" rx="1.5" />
              <rect x="14" y="3" width="7" height="7" rx="1.5" />
              <rect x="3" y="14" width="7" height="7" rx="1.5" />
              <rect x="14" y="14" width="7" height="7" rx="1.5" />
            </svg>
            <svg
              v-else-if="item.name === 'tasks'"
              width="18"
              height="18"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.7"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <path d="M9 4.5h9.5v15H5.5v-15H8" />
              <rect x="8" y="3" width="7" height="3.2" rx="1" />
              <path d="M8.5 11.5l1.6 1.6 3-3.2" />
              <path d="M8.5 17h6" />
            </svg>
            <svg
              v-else-if="item.name === 'media'"
              width="18"
              height="18"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.7"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <rect x="2.5" y="5" width="19" height="14" rx="2.5" />
              <path d="M10 9.5l5 2.5-5 2.5z" />
            </svg>
            <svg
              v-else-if="item.name === 'records'"
              width="18"
              height="18"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.7"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <rect x="4" y="3" width="16" height="18" rx="2.5" />
              <path d="M8.5 8.5h7M8.5 12h7M8.5 15.5h4" />
            </svg>
            <svg
              v-else
              width="18"
              height="18"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.7"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <path d="M4 6.5h10M4 12h7M4 17.5h5" />
              <path d="M14.5 15.5l2 2 3.5-4" />
            </svg>
            <span>{{ item.label }}</span>
          </button>
        </template>
      </RouterLink>
    </nav>

    <div class="lp-sidebar-foot">
      <div class="t"><span class="lp-dot"></span>{{ sourceFoot.title }}</div>
      <div class="d">{{ sourceFoot.desc }}</div>
    </div>
  </aside>
</template>
