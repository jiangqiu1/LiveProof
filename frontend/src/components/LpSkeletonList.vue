<script setup lang="ts">
/**
 * 列表加载骨架：呼吸动画的灰条行，形状对齐「图标点 + 两行文字」的列表项。
 *
 * <p>只在首屏加载（列表还没有任何数据）时使用；刷新已有内容时保持旧内容可见，
 * 不闪骨架（避免「看得好好的一下变空白」）。{@code rows} 控制行数，面板内用 3~4，
 * 页面级用 4~6。
 */
withDefaults(defineProps<{ rows?: number }>(), { rows: 4 })
</script>

<template>
  <div class="lp-skel-list" aria-hidden="true">
    <div v-for="i in rows" :key="i" class="lp-skel-row">
      <span class="lp-skel-dot" />
      <span class="lp-skel-lines">
        <span class="lp-skel lp-skel-line is-main" />
        <span class="lp-skel lp-skel-line is-sub" :style="{ width: 58 + ((i * 13) % 26) + '%' }" />
      </span>
    </div>
  </div>
</template>
