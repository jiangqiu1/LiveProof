<script setup lang="ts">
import AppSidebar from '@/components/AppSidebar.vue'
</script>

<template>
  <div class="lp-shell">
    <AppSidebar />
    <main class="lp-main">
      <!-- 页面切换过渡：out-in 保证两屏不重叠；样式见 main.css 动效体系段落 -->
      <RouterView v-slot="{ Component }">
        <Transition name="lp-page" mode="out-in">
          <component :is="Component" />
        </Transition>
      </RouterView>
    </main>
  </div>
</template>
