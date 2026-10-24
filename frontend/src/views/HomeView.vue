<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchCases, fetchMaterials } from '@/api'
import { describeError } from '@/api/client'
import LpSkeletonList from '@/components/LpSkeletonList.vue'
import LpState from '@/components/LpState.vue'
import type { CaseListItem, Material } from '@/types/api'
import { formatDateTime } from '@/constants/meta'

/**
 * 产品首页。
 *
 * <p>职责只有一件事：把人送到该去的地方。三种入口——继续上次的核验、新建项目、进示例。
 * 真正的核验工作全部在 `/workbench`，本页不做任何核验操作。
 *
 * <p><b>不编造任何数据</b>：卡片上的时长、事项数、已核验数、字幕可用性、时间
 * 全部来自 {@code /api/materials} 与 {@code /api/cases}；读不到就显示空态，
 * 不用占位假值撑版面。时间还必须区分「上次编辑」与「创建于」——
 * 素材刚上传、事项一条没改时，说「上次编辑 3 小时前」就是假话。
 */

const router = useRouter()

const materials = ref<Material[]>([])
const cases = ref<CaseListItem[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

/**
 * 上次停留的位置（工作台在切换事项时写入）。
 *
 * <p>只存素材 id 与事项 id，不含任何用户内容。首页读取后会校验该事项仍然存在、
 * 且确实属于这个素材，才拿它做深链接；否则退回按素材范围进入，不让用户撞 404。
 */
const LAST_KEY = 'lp.lastLocation'

interface LastLocation {
  materialId: string
  caseId: string | null
}

const lastLocation = ref<LastLocation | null>(null)

function readLastLocation(): LastLocation | null {
  try {
    const raw = localStorage.getItem(LAST_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<LastLocation> | null
    if (!parsed || typeof parsed.materialId !== 'string' || !parsed.materialId) return null
    return {
      materialId: parsed.materialId,
      caseId: typeof parsed.caseId === 'string' ? parsed.caseId : null,
    }
  } catch {
    // 内容被手动改坏 / 存储不可用：当作没有记录，不影响首页其余部分
    return null
  }
}

interface Project {
  material: Material
  total: number
  reviewed: number
  /** 该素材下所有事项的最后修改时间 */
  lastCaseUpdate: string | null
  /** 排序键：真实用户行为的时间（ISO 字符串可直接比较） */
  sortKey: string
}

function laterOf(a: string | null, b: string | null): string | null {
  if (!a) return b
  if (!b) return a
  return a > b ? a : b
}

/**
 * 按素材聚合出「核验项目」。
 *
 * <p>只收 {@code status=ready} 的素材：转码中和失败的根本不能核验，
 * 放进「继续」会让用户点进去看不到视频。
 *
 * <p>排序键只用「事项最后修改时间 → 素材创建时间」，<b>刻意不用
 * {@code material.updatedAt}</b>：那一列是 {@code ON UPDATE CURRENT_TIMESTAMP}，
 * 转码状态轮询、后端重启时的状态同步都会把它推到当下，与用户做过什么无关。
 * 拿它排序会让首页的「继续」跳到一个用户根本没动过的项目。
 */
const projects = computed<Project[]>(() => {
  const byMaterial = new Map<string, CaseListItem[]>()
  for (const row of cases.value) {
    const key = row.materialId ?? ''
    const bucket = byMaterial.get(key)
    if (bucket) bucket.push(row)
    else byMaterial.set(key, [row])
  }

  return materials.value
    .filter((material) => material.status === 'ready')
    .map((material) => {
      const rows = byMaterial.get(material.id) ?? []
      let lastCaseUpdate: string | null = null
      let reviewed = 0
      for (const row of rows) {
        lastCaseUpdate = laterOf(lastCaseUpdate, row.updatedAt)
        if (row.reviewStatus === 'reviewed') reviewed += 1
      }
      return {
        material,
        total: rows.length,
        reviewed,
        lastCaseUpdate,
        sortKey: lastCaseUpdate ?? material.createdAt ?? '',
      }
    })
    .sort((a, b) => (a.sortKey < b.sortKey ? 1 : a.sortKey > b.sortKey ? -1 : 0))
})

/** 主卡：优先上次真正待过的素材，其次最近有变动的那个 */
const primary = computed<Project | null>(() => {
  const list = projects.value
  if (list.length === 0) return null
  const remembered = lastLocation.value
  if (remembered) {
    const hit = list.find((item) => item.material.id === remembered.materialId)
    if (hit) return hit
  }
  return list[0]
})

/** 主卡之外还有几个项目，只提示数量，不铺开抢「继续」的注意力 */
const otherCount = computed(() => Math.max(0, projects.value.length - (primary.value ? 1 : 0)))

/** 相对时间：7 天内说「几小时前」，更早直接给日期，避免「32 天前」这种没人能换算的说法 */
function relativeTime(value: string | null): string {
  if (!value) return ''
  const at = new Date(value)
  if (Number.isNaN(at.getTime())) return ''
  const diff = Date.now() - at.getTime()
  // 服务器时间超前（时钟不同步）时不编「刚刚」，如实给日期
  if (diff < 0) return formatDateTime(value)
  const minute = 60_000
  const hour = 60 * minute
  const day = 24 * hour
  if (diff < minute) return '刚刚'
  if (diff < hour) return `${Math.floor(diff / minute)} 分钟前`
  if (diff < day) return `${Math.floor(diff / hour)} 小时前`
  if (diff < 7 * day) return `${Math.floor(diff / day)} 天前`
  return formatDateTime(value)
}

/**
 * 时间标签的含义。
 *
 * <p>事项的最后修改晚于素材创建时间，才算「编辑过」；否则只能说「创建于」。
 * 这两句不能混用：刚上传的素材一条事项都没动过，写成「上次编辑」会让用户
 * 以为自己之前改过什么。
 */
function timeKind(project: Project): string {
  const edited = project.lastCaseUpdate
  const created = project.material.createdAt
  if (edited && (!created || edited > created)) return '上次编辑'
  return '创建于'
}

function timeValue(project: Project): string {
  const edited = project.lastCaseUpdate
  const created = project.material.createdAt
  if (edited && (!created || edited > created)) return relativeTime(edited)
  return relativeTime(created)
}

/**
 * 「继续」要落到哪一条事项。
 *
 * <p>上次停留的事项仍然存在且属于这个素材时才带 {@code ?case=}，直达那一条；
 * 否则只带 {@code ?material=}，让工作台自己选该素材下的第一条——
 * 用已经删掉的事项 id 做深链接，工作台只能走「不存在」回退，白弹一次提示。
 */
function resolveCaseId(project: Project): string | null {
  const remembered = lastLocation.value
  if (!remembered || remembered.materialId !== project.material.id || !remembered.caseId) return null
  const exists = cases.value.some(
    (row) => row.id === remembered.caseId && row.materialId === project.material.id,
  )
  return exists ? remembered.caseId : null
}

function continueProject(project: Project) {
  const caseId = resolveCaseId(project)
  router.push({
    name: 'workbench',
    query: caseId ? { case: caseId } : { material: project.material.id },
  })
}

function createProject() {
  router.push({ name: 'new-task' })
}

/**
 * 「体验示例项目」必须真的进示例素材。
 *
 * <p>不带参数时工作台按列表第一条自动选中，用户上传过素材后第一条可能是他自己的视频，
 * 点「示例」进去却是自己的项目，入口名与结果不符。
 */
function openSample() {
  const builtin = materials.value.find((item) => item.builtin && item.status === 'ready')
  router.push(builtin ? { name: 'workbench', query: { material: builtin.id } } : { name: 'workbench' })
}

function durationLabel(project: Project): string {
  const sec = project.material.durationSec
  if (!sec) return '时长未知'
  return `${Math.round(sec)} 秒`
}

async function load() {
  loading.value = true
  error.value = null
  try {
    const [list, rows] = await Promise.all([fetchMaterials(), fetchCases('cf')])
    materials.value = list
    cases.value = rows
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  lastLocation.value = readLastLocation()
  void load()
})
</script>

<template>
  <div class="lp-page lp-home">
    <!-- Hero：产品是什么 + 两个入口 -->
    <section class="lp-home-hero lp-rise">
      <div class="lp-home-hero-copy">
        <span class="lp-home-kicker">VIDEO EVIDENCE WORKSPACE</span>
        <h1 class="lp-home-title">让每一条产品承诺，<br />都有证据可查。</h1>
        <p class="lp-home-sub">从演示视频中定位证据，核验每一项功能承诺，并整理出尚待确认的问题。</p>
        <div class="lp-home-actions">
          <button type="button" class="lp-btn lp-btn-primary" @click="createProject">
            新建核验项目
          </button>
          <button type="button" class="lp-btn" @click="openSample">体验示例项目</button>
        </div>
      </div>

      <!-- 纯示意，不承载任何真实数据：用界面语言说明「视频 → 字幕 → 逐条核验」 -->
      <div class="lp-home-art lp-d1" aria-hidden="true">
        <div class="lp-home-frame">
          <div class="lp-home-screen">
            <svg width="30" height="30" viewBox="0 0 24 24" fill="none">
              <circle cx="12" cy="12" r="10.5" stroke="rgba(255,255,255,0.5)" stroke-width="1.2" />
              <path d="M10 8.2l6 3.8-6 3.8z" fill="#ffffff" />
            </svg>
          </div>
          <div class="lp-home-bar"><span /></div>
        </div>
        <div class="lp-home-checks">
          <div class="lp-home-checks-head">逐条核验</div>
          <div class="lp-home-check"><i class="on" /><span /></div>
          <div class="lp-home-check"><i class="on" /><span /></div>
          <div class="lp-home-check"><i /><span class="short" /></div>
        </div>
      </div>
    </section>

    <!-- 继续你的核验工作 -->
    <section class="lp-home-section lp-rise lp-d2">
      <div class="lp-home-section-head">
        <h2>继续你的核验工作</h2>
        <RouterLink class="lp-home-more" :to="{ name: 'tasks' }">全部项目 →</RouterLink>
      </div>

      <div v-if="loading" class="lp-home-card is-plain" style="padding: 8px 6px">
        <LpSkeletonList :rows="2" />
      </div>

      <LpState
        v-else-if="error"
        variant="error"
        title="首页数据读取失败"
        :description="error"
      >
        <template #actions>
          <button type="button" class="lp-btn lp-btn-primary" :disabled="loading" @click="load">
            {{ loading ? '重试中…' : '重新加载' }}
          </button>
        </template>
      </LpState>

      <LpState
        v-else-if="!primary"
        title="还没有可核验的素材"
        description="上传一段演示视频，或直接使用内置示例。素材转码就绪后才会出现在这里——转码中的视频还不能核验。"
      >
        <template #actions>
          <button type="button" class="lp-btn lp-btn-primary" @click="createProject">
            新建核验项目
          </button>
        </template>
      </LpState>

      <button v-else-if="primary" type="button" class="lp-home-project" @click="continueProject(primary)">
        <div class="lp-home-thumb">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
            <path d="M9 6.5l9 5.5-9 5.5z" fill="#ffffff" opacity="0.92" />
          </svg>
          <span class="lp-home-thumb-dur">{{ durationLabel(primary) }}</span>
        </div>

        <div class="lp-home-project-body">
          <div class="lp-home-project-name">
            {{ primary.material.originalName ?? primary.material.id }}
          </div>
          <div class="lp-home-project-meta">
            <span>{{ primary.material.builtin ? '内置示例' : '保利威点播' }}</span>
            <span class="dot">·</span>
            <span>{{ primary.total }} 条核验事项</span>
          </div>
          <div class="lp-home-project-tags">
            <span class="lp-pill mint">已就绪</span>
            <span
              class="lp-pill"
              :class="primary.material.subtitleAvailable ? 'ghost' : 'warn'"
            >
              {{ primary.material.subtitleAvailable ? '本地字幕可用' : '无字幕，仅能按时间点核对' }}
            </span>
            <span v-if="primary.total > 0" class="lp-pill ghost">
              已核验 {{ primary.reviewed }} / {{ primary.total }}
            </span>
            <span v-else class="lp-pill ghost">还没有录入核验事项</span>
          </div>
        </div>

        <div class="lp-home-project-time">
          <span class="k">{{ timeKind(primary) }}</span>
          <span class="v">{{ timeValue(primary) || '时间未知' }}</span>
        </div>
        <span class="lp-home-project-arrow">→</span>
      </button>

      <div v-if="otherCount > 0" class="lp-home-others">
        另有 {{ otherCount }} 个核验项目，
        <RouterLink :to="{ name: 'tasks' }">在核验项目里查看</RouterLink>
      </div>
    </section>
  </div>
</template>
