<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { completeTodo, fetchCases, fetchTodos, updateTodo } from '@/api'
import { describeError } from '@/api/client'
import LpSkeletonList from '@/components/LpSkeletonList.vue'
import LpState from '@/components/LpState.vue'
import type { CaseListItem, Todo, TodoStatus } from '@/types/api'
import { TODO_STATUS_LABEL, TODO_STATUS_OPTIONS, formatDateTime, todoTone } from '@/constants/meta'
import TodoFormDialog from '@/components/TodoFormDialog.vue'

const todoText = (value: string) => TODO_STATUS_LABEL[value as TodoStatus]

const todos = ref<Todo[]>([])
const cases = ref<CaseListItem[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const statusFilter = ref<TodoStatus | 'all'>('all')
const busyId = ref<number | null>(null)

const dialogVisible = ref(false)
const editing = ref<Todo | null>(null)

const filtered = computed(() =>
  todos.value.filter((todo) => statusFilter.value === 'all' || todo.status === statusFilter.value),
)

const caseQuestion = computed(() => {
  const map = new Map<string, string>()
  cases.value.forEach((item) => map.set(item.id, item.question ?? item.id))
  return map
})

const stats = computed(() => ({
  open: todos.value.filter((todo) => todo.status === 'open').length,
  doing: todos.value.filter((todo) => todo.status === 'in_progress').length,
  done: todos.value.filter((todo) => todo.status === 'done').length,
}))

async function load() {
  loading.value = true
  error.value = null
  try {
    const [todoList, caseList] = await Promise.all([fetchTodos(), fetchCases('cf')])
    todos.value = todoList
    cases.value = caseList
  } catch (err) {
    error.value = describeError(err)
    ElMessage.error(error.value)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  dialogVisible.value = true
}

function openEdit(todo: Todo) {
  editing.value = todo
  dialogVisible.value = true
}

async function markDone(todo: Todo) {
  busyId.value = todo.id
  try {
    const updated = await completeTodo(todo.id)
    replace(updated)
    ElMessage.success('待办已完成')
  } catch (err) {
    ElMessage.error(describeError(err))
  } finally {
    busyId.value = null
  }
}

async function reopen(todo: Todo) {
  busyId.value = todo.id
  try {
    const updated = await updateTodo(todo.id, { status: 'open' })
    replace(updated)
    ElMessage.success('已重新打开')
  } catch (err) {
    ElMessage.error(describeError(err))
  } finally {
    busyId.value = null
  }
}

function replace(todo: Todo) {
  const index = todos.value.findIndex((item) => item.id === todo.id)
  if (index >= 0) todos.value[index] = todo
  else todos.value = [todo, ...todos.value]
}

function onSaved(todo: Todo) {
  replace(todo)
}

onMounted(load)
</script>

<template>
  <div class="lp-page">
    <header class="lp-page-head">
      <div>
        <div class="lp-crumb">首页 <span class="sep">/</span> 待办复盘</div>
        <h1 class="lp-page-title">待办复盘</h1>
        <p class="lp-page-sub">把尚未被演示证明的问题转成待办，逐条补录后再回到工作台改判。</p>
      </div>
      <div class="lp-page-actions">
        <button type="button" class="lp-btn" :disabled="loading" @click="load">
          {{ loading ? '刷新中…' : '刷新' }}
        </button>
        <button type="button" class="lp-btn lp-btn-primary" @click="openCreate">新建待办</button>
      </div>
    </header>

    <div class="lp-metric-row">
      <div class="lp-metric">
        <div class="k">待处理</div>
        <div class="v">{{ stats.open }}<span class="u">条</span></div>
      </div>
      <div class="lp-metric">
        <div class="k">进行中</div>
        <div class="v">{{ stats.doing }}<span class="u">条</span></div>
      </div>
      <div class="lp-metric">
        <div class="k">已完成</div>
        <div class="v">{{ stats.done }}<span class="u">条</span></div>
      </div>
      <div class="lp-metric">
        <div class="k">全部</div>
        <div class="v">{{ todos.length }}<span class="u">条</span></div>
      </div>
    </div>

    <section class="lp-card" style="flex: 1 1 auto; min-height: 0">
      <div class="lp-card-head">
        <div class="lp-card-title">待办列表</div>
        <el-select v-model="statusFilter" size="small" style="width: 140px">
          <el-option label="全部状态" value="all" />
          <el-option
            v-for="option in TODO_STATUS_OPTIONS"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </div>

      <div class="lp-page-body">
        <!-- 请求失败：整块替换表格（失败 ≠ 没有待办） -->
        <LpState
          v-if="error && todos.length === 0"
          variant="error"
          title="待办列表加载失败"
          :description="error"
        >
          <template #actions>
            <button type="button" class="lp-btn lp-btn-primary" :disabled="loading" @click="load">
              {{ loading ? '重试中…' : '重新加载' }}
            </button>
          </template>
        </LpState>
        <LpSkeletonList v-else-if="loading && todos.length === 0" :rows="5" />
        <!-- 筛选无命中 ≠ 没有待办：给清除筛选入口 -->
        <LpState
          v-else-if="todos.length > 0 && filtered.length === 0"
          variant="search"
          title="该状态下没有待办"
          description="换一个状态看看，或切回「全部状态」。"
        >
          <template #actions>
            <button type="button" class="lp-btn" @click="statusFilter = 'all'">查看全部状态</button>
          </template>
        </LpState>
        <LpState
          v-else-if="todos.length === 0"
          title="还没有待办"
          description="核验中发现没能被演示证明的问题时，在右栏点「创建后续待办」；也可以直接点右上角手动新建。"
        >
          <template #actions>
            <button type="button" class="lp-btn lp-btn-primary" @click="openCreate">新建待办</button>
          </template>
        </LpState>
        <template v-else>
          <div v-if="error" class="lp-note warn" style="margin: 12px">
            {{ error }}
          </div>
          <el-table :data="filtered" size="small" style="width: 100%" empty-text="暂无待办">
          <el-table-column label="标题" min-width="240">
            <template #default="{ row }">
              <div style="font-weight: 500; color: var(--lp-ink)">{{ row.title }}</div>
              <div v-if="row.detail" style="font-size: 11.5px; color: var(--lp-muted); margin-top: 2px">
                {{ row.detail }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="关联用例" min-width="200">
            <template #default="{ row }">
              <div style="font-size: 12px; color: var(--lp-ink-soft)">
                {{ caseQuestion.get(row.caseId) ?? row.caseId }}
              </div>
              <div style="font-size: 11px; color: var(--lp-muted-soft)">{{ row.caseId }}</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <span class="lp-pill" :class="todoTone(row.status)">{{ todoText(row.status) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="150">
            <template #default="{ row }">
              <span style="color: var(--lp-muted)">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="完成时间" width="150">
            <template #default="{ row }">
              <span style="color: var(--lp-muted)">{{ formatDateTime(row.completedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <div style="display: flex; gap: 4px">
                <button
                  v-if="row.status !== 'done'"
                  type="button"
                  class="lp-btn lp-btn-text"
                  style="height: 24px"
                  :disabled="busyId === row.id"
                  @click="markDone(row)"
                >
                  完成
                </button>
                <button
                  v-else
                  type="button"
                  class="lp-btn lp-btn-text"
                  style="height: 24px"
                  :disabled="busyId === row.id"
                  @click="reopen(row)"
                >
                  重开
                </button>
                <button type="button" class="lp-btn lp-btn-text" style="height: 24px" @click="openEdit(row)">
                  编辑
                </button>
              </div>
            </template>
          </el-table-column>
        </el-table>
        </template>
      </div>
    </section>

    <TodoFormDialog
      v-model="dialogVisible"
      :todo="editing"
      :cases="cases"
      :preset-case-id="cases[0]?.id ?? null"
      @saved="onSaved"
    />
  </div>
</template>
