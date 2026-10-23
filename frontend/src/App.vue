<script setup lang="ts">
import AppSidebar from '@/components/AppSidebar.vue'
</script>

<template>
  <div class="lp-shell">
    <AppSidebar />
    <main class="lp-main">
      <RouterView />
    </main>
  </div>
</template>
