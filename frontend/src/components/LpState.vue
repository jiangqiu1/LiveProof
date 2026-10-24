<script setup lang="ts">
/**
 * 统一的状态占位组件：空态 / 错误态 / 搜索无结果。
 *
 * <p>三种状态共用一套布局（图标 + 标题 + 说明 + 操作区），只换图标与文案语义，
 * 保证全站状态页长得一样、行为可预期。使用约定：
 * <ul>
 *   <li>{@code empty}：数据确实不存在。文案 = 还没有什么 + 怎么办，操作区放引导按钮；</li>
 *   <li>{@code error}：请求失败。文案 = {@code describeError} 的事实，操作区放「重试」
 *       （重试函数各页不同，由父级通过 actions 插槽提供，组件不假装知道怎么重试）；</li>
 *   <li>{@code search}：筛选/搜索无命中。操作区放「清除筛选」。</li>
 * </ul>
 * <p>紧凑模式（{@code compact}）用于面板内部（工作台左栏、字幕面板等），图标与间距缩小。
 */
withDefaults(
  defineProps<{
    variant?: 'empty' | 'error' | 'search'
    title: string
    description?: string
    compact?: boolean
  }>(),
  { variant: 'empty', description: undefined, compact: false },
)
</script>

<template>
  <div class="lp-state" :class="[`is-${variant}`, { 'is-compact': compact }]">
    <span class="lp-state-icon" aria-hidden="true">
      <!-- 空态：打开的盒子 -->
      <svg
        v-if="variant === 'empty'"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.6"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <path d="M3.3 7.6 12 3l8.7 4.6v8.8L12 21l-8.7-4.6Z" />
        <path d="M3.3 7.6 12 12.2l8.7-4.6M12 12.2V21" />
        <path d="M7.6 5.3l8.7 4.6" />
      </svg>
      <!-- 错误：云 + 断开的连线 -->
      <svg
        v-else-if="variant === 'error'"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.6"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <path d="M6.5 18a4 4 0 0 1-.4-8 5.5 5.5 0 0 1 10.7-1.2A4.3 4.3 0 0 1 17.5 18" />
        <path d="m9.5 13.5 2 2 3-3.5" />
      </svg>
      <!-- 搜索：放大镜 -->
      <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
        <circle cx="11" cy="11" r="6.5" />
        <path d="m20 20-4.4-4.4" />
      </svg>
    </span>
    <div class="lp-state-body">
      <div class="lp-state-title">{{ title }}</div>
      <div v-if="description" class="lp-state-desc">{{ description }}</div>
      <div v-if="$slots.actions" class="lp-state-actions">
        <slot name="actions" />
      </div>
    </div>
  </div>
</template>
