<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { downloadCaseImportTemplate, importCases, previewCaseImport } from '@/api'
import { describeError } from '@/api/client'
import type { CaseImportPolicy, CaseImportPreview, CaseImportResult, Material } from '@/types/api'

const props = defineProps<{
  modelValue: boolean
  materialId: string | null
  materials: Material[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'imported', result: CaseImportResult): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const file = ref<File | null>(null)
const preview = ref<CaseImportPreview | null>(null)
const result = ref<CaseImportResult | null>(null)
const policy = ref<CaseImportPolicy>('strict')
const busy = ref(false)
const error = ref<string | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

const activeMaterial = computed(() =>
  props.materialId ? props.materials.find((item) => item.id === props.materialId) ?? null : null,
)

/** strict 下只要有一行非法就不允许确认，避免「以为导入了」 */
const canConfirm = computed(() => {
  const p = preview.value
  if (!p) return false
  if (policy.value === 'strict') return p.invalidCount === 0 && p.validCount > 0
  return p.validCount > 0
})

const confirmHint = computed(() => {
  const p = preview.value
  if (!p) return ''
  if (p.validCount === 0) return '没有可导入的行'
  if (policy.value === 'strict' && p.invalidCount > 0) {
    return `严格模式下一旦有非法行就整批拒绝（当前 ${p.invalidCount} 行有问题），请修正 CSV 或改用「跳过非法行」`
  }
  return ''
})

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    file.value = null
    preview.value = null
    result.value = null
    error.value = null
    policy.value = 'strict'
  },
)

function pickFile() {
  fileInput.value?.click()
}

async function onPick(event: Event) {
  const input = event.target as HTMLInputElement
  const picked = input.files?.[0]
  input.value = ''
  if (!picked) return
  file.value = picked
  result.value = null
  await runPreview()
}

/** 预览：只解析与校验，服务端明确不写库 */
async function runPreview() {
  if (!file.value || !props.materialId) return
  busy.value = true
  error.value = null
  try {
    preview.value = await previewCaseImport(props.materialId, file.value)
  } catch (e) {
    preview.value = null
    error.value = describeError(e)
  } finally {
    busy.value = false
  }
}

async function confirmImport() {
  if (!file.value || !props.materialId || !canConfirm.value || busy.value) return
  busy.value = true
  error.value = null
  try {
    const imported = await importCases(props.materialId, file.value, policy.value)
    result.value = imported
    emit('imported', imported)
    ElMessage.success(`已导入 ${imported.imported} 条核验事项`)
    preview.value = null
    file.value = null
  } catch (e) {
    error.value = describeError(e)
  } finally {
    busy.value = false
  }
}

async function downloadTemplate() {
  try {
    const blob = await downloadCaseImportTemplate()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = '核验事项模板.csv'
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    error.value = describeError(e)
  }
}

function rowTone(row: { problems: string[] }): string {
  return row.problems.length === 0 ? 'mint' : 'warn'
}
</script>

<template>
  <el-dialog v-model="visible" title="导入核验事项（CSV）" width="720px" append-to-body>
    <div style="display: flex; flex-direction: column; gap: 12px">
      <div class="lp-note" style="font-size: 11.5px">
        预览阶段<b>不会写入任何数据</b>，确认后才落库。CSV 导入是批量录入，
        不是 AI 自动生成核验结论：导入的事项同样是「尚未核验」。
      </div>

      <div class="lp-field">
        <div class="lp-field-label"><span>归属素材</span></div>
        <div style="font-size: 12.5px">
          <template v-if="activeMaterial">
            {{ activeMaterial.originalName ?? activeMaterial.id }}
          </template>
          <template v-else>未选择素材（导入必须指定素材）</template>
        </div>
      </div>

      <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
        <input ref="fileInput" type="file" accept=".csv,text/csv" hidden @change="onPick" />
        <button type="button" class="lp-btn lp-btn-primary" :disabled="busy" @click="pickFile">
          {{ file ? '重新选择 CSV' : '选择 CSV 文件' }}
        </button>
        <button type="button" class="lp-btn" @click="downloadTemplate">下载模板</button>
        <button
          v-if="file"
          type="button"
          class="lp-btn"
          :disabled="busy"
          @click="runPreview"
        >
          重新预览
        </button>
        <span v-if="file" style="font-size: 11.5px; color: var(--lp-muted)">
          {{ file.name }} · {{ (file.size / 1024).toFixed(1) }} KB
        </span>
      </div>

      <div v-if="busy" class="lp-note">正在解析与校验…</div>
      <div v-if="error" class="lp-note warn">{{ error }}</div>

      <template v-if="preview">
        <div style="display: flex; gap: 8px; flex-wrap: wrap; align-items: center">
          <span class="lp-pill mint">将新增 {{ preview.validCount }} 条</span>
          <span class="lp-pill" :class="preview.duplicateCount > 0 ? 'warn' : 'ghost'">
            重复 {{ preview.duplicateCount }} 条
          </span>
          <span class="lp-pill" :class="preview.invalidCount > 0 ? 'warn' : 'ghost'">
            有错误 {{ preview.invalidCount }} 行
          </span>
          <span class="lp-pill ghost">共 {{ preview.totalRows }} 行</span>
        </div>

        <div v-if="preview.unmappedColumns.length > 0" class="lp-note" style="font-size: 11.5px">
          以下列名未被识别，将被忽略：{{ preview.unmappedColumns.join('、') }}
        </div>

        <div class="lp-table-wrap">
          <table class="lp-table">
            <thead>
              <tr>
                <th style="width: 56px">行号</th>
                <th style="width: 72px">状态</th>
                <th>问题</th>
                <th>承诺</th>
                <th style="width: 110px">时间区间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in preview.rows" :key="row.line">
                <td>{{ row.line }}</td>
                <td>
                  <span class="lp-pill" :class="rowTone(row)">{{ row.problems.length === 0 ? '可导入' : '将跳过' }}</span>
                </td>
                <td>
                  <div>{{ row.question ?? '—' }}</div>
                  <div v-if="row.problems.length > 0" class="lp-row-problem">
                    {{ row.problems.join('；') }}
                  </div>
                </td>
                <td>{{ row.claim ?? '—' }}</td>
                <td>
                  <template v-if="row.startSec !== null && row.endSec !== null">
                    {{ row.startSec }}s – {{ row.endSec }}s
                  </template>
                  <template v-else>未指定</template>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="lp-field">
          <div class="lp-field-label"><span>冲突处理策略</span></div>
          <el-radio-group v-model="policy" size="small">
            <el-radio-button value="strict">严格：有非法行则整批拒绝</el-radio-button>
            <el-radio-button value="skip_invalid">跳过非法行，写入合法行</el-radio-button>
          </el-radio-group>
          <div v-if="confirmHint" class="lp-note warn" style="margin-top: 6px; font-size: 11.5px">
            {{ confirmHint }}
          </div>
        </div>
      </template>

      <div v-if="result" class="lp-note" style="font-size: 12px">
        上次导入：写入 {{ result.imported }} 条，跳过 {{ result.skippedCount }} 行（策略 {{ result.policy }}）。
        <template v-if="result.skipped.length > 0">
          跳过行号：{{ result.skipped.map((s) => s.line).join('、') }}。
        </template>
      </div>
    </div>

    <template #footer>
      <div style="display: flex; justify-content: flex-end; gap: 8px">
        <button type="button" class="lp-btn" @click="visible = false">关闭</button>
        <button
          type="button"
          class="lp-btn lp-btn-primary"
          :disabled="busy || !canConfirm"
          @click="confirmImport"
        >
          {{ busy ? '导入中…' : '确认导入' }}
        </button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.lp-table-wrap {
  max-height: 260px;
  overflow: auto;
  border: 1px solid var(--lp-border, #e5ece9);
  border-radius: 10px;
}
.lp-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.lp-table th,
.lp-table td {
  padding: 7px 9px;
  text-align: left;
  vertical-align: top;
  border-bottom: 1px solid var(--lp-border, #e5ece9);
}
.lp-table th {
  position: sticky;
  top: 0;
  background: #f7faf9;
  font-weight: 600;
  color: var(--lp-ink, #081a26);
}
.lp-table tr:last-child td {
  border-bottom: none;
}
.lp-row-problem {
  margin-top: 3px;
  color: #b4700f;
  font-size: 11.5px;
}
</style>