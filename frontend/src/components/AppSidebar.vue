<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import logoUrl from '@/assets/logo-liveproof.png'
import markUrl from '@/assets/logo-mark.png'

const route = useRoute()

/**
 * 侧栏收起状态。
 *
 * <p>收起后只留 68px 的图标栏，把 148px 还给中栏（工作台三栏里最需要宽度的一栏）。
 * 状态存 localStorage，刷新与跨页面跳转都保持用户的选择，不每次重置成展开。
 */
const COLLAPSE_KEY = 'lp.sidebar.collapsed'
const collapsed = ref(false)

onMounted(() => {
  collapsed.value = localStorage.getItem(COLLAPSE_KEY) === '1'
})
watch(collapsed, (value) => {
  localStorage.setItem(COLLAPSE_KEY, value ? '1' : '0')
})

/**
 * 导航项。
 *
 * <p>「首页」与「工作台」是两层：首页负责进入（继续上次核验、新建、体验示例），
 * 工作台负责干活（三栏核验）。根路径曾长期就是工作台，现在让给首页，
 * 工作台迁到 {@code /workbench}，深链接兼容由 router 的守卫负责。
 *
 * <p>`/tasks` 改叫「核验项目」：它本质是一个素材一个项目的清单，
 * 继续叫「核验任务」会和新出现的「工作台」抢语义（真正干活的是工作台）。
 */
const items = [
  { name: 'home', label: '首页', to: '/' },
  { name: 'workbench', label: '工作台', to: '/workbench' },
  { name: 'tasks', label: '核验项目', to: '/tasks' },
  { name: 'media', label: '素材库', to: '/materials' },
  { name: 'records', label: '核验记录', to: '/records' },
  { name: 'todos', label: '待办复盘', to: '/todos' },
] as const

/**
 * 导航高亮归属。
 *
 * <p>new-task 是核验项目的子页面，高亮归到父项，否则侧栏会全暗。
 * 其余按路由 name 一一对应：`/` 是 home、`/workbench` 是 workbench，
 * 两者不会互相串高亮。
 */
const activeName = computed(() => {
  const name = (route.name as string) ?? 'home'
  return name === 'new-task' ? 'tasks' : name
})
</script>

<template>
  <aside class="lp-sidebar" :class="{ 'is-collapsed': collapsed }">
    <div class="lp-brand">
      <!-- 展开态用完整 Logo；收起态换预乘 alpha 的图形标，避免 CSS 裁切在边缘带出黑像素 -->
      <img class="lp-logo-full" :src="logoUrl" alt="LiveProof 视频演示证据核验" />
      <img class="lp-logo-mark" :src="markUrl" alt="LiveProof" />
    </div>

    <button
      type="button"
      class="lp-collapse"
      :title="collapsed ? '展开侧栏' : '收起侧栏'"
      :aria-label="collapsed ? '展开侧栏' : '收起侧栏'"
      :aria-expanded="!collapsed"
      @click="collapsed = !collapsed"
    >
      <svg
        width="12"
        height="12"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2.2"
        stroke-linecap="round"
        stroke-linejoin="round"
        :style="{ transform: collapsed ? 'rotate(180deg)' : 'none' }"
      >
        <path d="M14 5l-7 7 7 7" />
      </svg>
    </button>

    <nav class="lp-nav">
      <RouterLink v-for="item in items" :key="item.name" :to="item.to" custom>
        <template #default="{ navigate, isExactActive }">
          <button
            type="button"
            class="lp-nav-item"
            :class="{ 'is-active': isExactActive || activeName === item.name }"
            :title="collapsed ? item.label : undefined"
            @click="navigate"
          >
            <svg
              v-if="item.name === 'home'"
              width="18"
              height="18"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.7"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <path d="M3.5 10.6L12 3.6l8.5 7" />
              <path d="M5.8 9.4V20h12.4V9.4" />
              <path d="M10 20v-5.2h4V20" />
            </svg>
            <svg
              v-else-if="item.name === 'workbench'"
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
            <span class="lp-nav-label">{{ item.label }}</span>
          </button>
        </template>
      </RouterLink>
    </nav>
  </aside>
</template>
