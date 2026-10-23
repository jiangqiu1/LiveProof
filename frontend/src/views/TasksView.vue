<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { fetchCases, fetchMaterialCapabilities, fetchMaterials } from '@/api'
import { describeError } from '@/api/client'
import type { CaseListItem, Material, MaterialCapabilities } from '@/types/api'
import { formatDateTime } from '@/constants/meta'

const router = useRouter()

const materials = ref<Material[]>([])
const capabilities = ref<MaterialCapabilities | null>(null)
const casesByMaterial = ref<Record<string, number>>({})
const totalCases = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)

const builtinMaterial = computed(() => materials.value.find((item) => item.builtin) ?? null)
const builtinCaseCount = computed(() => {
  const id = builtinMaterial.value?.id
  return id ? casesByMaterial.value[id] ?? 0 : 0
})

const cloudMaterials = computed(() => materials.value.filter((item) => !item.builtin))

const STATUS_LABEL: Record<string, string> = {
  transcoding: '转码中',
  ready: '已就绪',
  failed: '转码失败',
  deleted: '已删除',
}

async function load() {
  loading.value = true
  error.value = null
  try {
    const [list, caps] = await Promise.all([fetchMaterials(), fetchMaterialCapabilities()])
    materials.value = list
    capabilities.value = caps
    const counts: Record<string, number> = {}
    const rows: CaseListItem[] = await fetchCases('cf')
    totalCases.value = rows.length
    for (const row of rows) {
      const key = row.materialId ?? 'unknown'
      counts[key] = (counts[key] ?? 0) + 1
    }
    casesByMaterial.value = counts
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

function openSample() {
  router.push({ name: 'workbench' })
}

function openMaterial(material: Material) {
  router.push({ name: 'workbench', query: { material: material.id } })
}

function newTask() {
  if (!capabilities.value?.uploadEnabled) {
    ElMessage.warning('后端未配置保利威点播上传凭据，无法新建云端素材；仍可体验示例或使用已有素材')
  }
  router.push({ name: 'new-task' })
}

onMounted(load)
</script>

<template>
  <div class="lp-page">
    <header class="lp-page-head">
      <div>
        <div class="lp-crumb">工作台 <span class="sep">/</span> 核验任务</div>
        <h1 class="lp-page-title">核验任务</h1>
        <p class="lp-page-sub">
          一份演示视频对应一个核验任务：先准备素材与字幕，再录入待核验的产品承诺。
        </p>
      </div>
      <div class="lp-page-actions">
        <button type="button" class="lp-btn lp-btn-primary" @click="newTask">新建核验任务</button>
      </div>
    </header>

    <div class="lp-page-body">
      <div v-if="error" class="lp-note warn" style="margin-bottom: 14px">{{ error }}</div>

      <div class="lp-entry-grid" style="margin-bottom: 16px">
        <button type="button" class="lp-entry-card" @click="openSample">
          <div class="t">体验内置示例</div>
          <div class="d">
            零配置直接进入工作台：内置演示视频
            {{ builtinMaterial?.originalName ?? 'candidateflow-demo.mp4' }}
            配 {{ builtinCaseCount }} 条核验事项，字幕与视频均来自本地白名单，不调用任何云端服务。
          </div>
          <div class="f">
            <span class="lp-pill mint">离线可用</span>
            <span class="lp-pill ghost">{{ builtinCaseCount }} 条事项</span>
            <span class="lp-pill ghost">本地 SRT</span>
          </div>
        </button>

        <button type="button" class="lp-entry-card" @click="newTask">
          <div class="t">新建核验任务</div>
          <div class="d">
            上传一段演示视频到保利威点播，等转码完成后准备字幕（智能字幕或 SRT），
            再手动录入或批量导入待核验的产品承诺。
          </div>
          <div class="f">
            <span class="lp-pill" :class="capabilities?.uploadEnabled ? 'mint' : 'warn'">
              {{ capabilities?.uploadEnabled ? '上传通道就绪' : '上传未配置' }}
            </span>
            <span class="lp-pill ghost">保利威点播</span>
          </div>
        </button>
      </div>

      <section class="lp-card">
        <div class="lp-card-head">
          <div class="lp-card-title">已有素材</div>
          <span class="lp-pill ghost">{{ materials.length }} 个 · 共 {{ totalCases }} 条事项</span>
        </div>
        <div style="padding: 10px 12px 14px; display: flex; flex-direction: column; gap: 8px">
          <div v-if="loading && materials.length === 0" class="lp-empty">正在加载素材…</div>
          <div v-else-if="materials.length === 0" class="lp-empty">还没有素材</div>
          <div
            v-for="item in materials"
            :key="item.id"
            class="lp-material-item"
            @click="openMaterial(item)"
          >
            <div class="lp-material-body">
              <div class="lp-material-name">{{ item.originalName ?? item.id }}</div>
              <div class="lp-material-meta">
                <span class="lp-pill" :class="item.builtin ? 'ghost' : 'mint'">
                  {{ item.builtin ? '内置' : '保利威' }}
                </span>
                <span
                  class="lp-pill"
                  :class="item.status === 'ready' ? 'mint' : item.status === 'failed' ? 'warn' : 'ink'"
                >
                  {{ STATUS_LABEL[item.status] ?? item.status }}
                </span>
                <span>{{ casesByMaterial[item.id] ?? 0 }} 条事项</span>
                <span v-if="item.durationSec">{{ item.durationSec }}s</span>
                <span v-if="item.srtFileName">SRT {{ item.srtFileName }}</span>
                <span v-else>无 SRT</span>
                <span v-if="item.createdAt">{{ formatDateTime(item.createdAt) }}</span>
              </div>
            </div>
          </div>
          <div v-if="cloudMaterials.length === 0" class="lp-note" style="font-size: 11.5px">
            还没有上传过云端素材。新建任务时上传的视频会存入保利威点播，并出现在这里。
          </div>
        </div>
      </section>
    </div>
  </div>
</template>