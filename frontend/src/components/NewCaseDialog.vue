<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createCase } from '@/api'
import { describeError } from '@/api/client'
import type { CaseDetail, Material } from '@/types/api'

const props = defineProps<{
  modelValue: boolean
  /** 归属素材：新增事项必须落在某个素材下，客户端不能自带 video */
  materialId: string | null
  materials: Material[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'created', detail: CaseDetail): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const question = ref('')
const claim = ref('')
const startText = ref('')
const endText = ref('')
const submitting = ref(false)
const error = ref<string | null>(null)
/** 连续新增：勾选后保存不清空表单，便于一次录入多条 */
const keepOpen = ref(true)
const createdCount = ref(0)

const activeMaterial = computed(() =>
  props.materialId ? props.materials.find((item) => item.id === props.materialId) ?? null : null,
)
const materialReady = computed(() => activeMaterial.value?.status === 'ready')

/** 支持纯秒、mm:ss、hh:mm:ss 三种写法，与后端 CSV 解析口径一致 */
function parseSec(raw: string): { ok: true; value: number | null } | { ok: false; message: string } {
  const text = raw.trim()
  if (!text) return { ok: true, value: null }
  const parts = text.split(':')
  if (parts.length > 3) return { ok: false, message: '时间格式最多支持 hh:mm:ss' }
  const nums = parts.map((part) => Number(part.trim()))
  if (nums.some((n) => Number.isNaN(n) || n < 0)) {
    return { ok: false, message: '时间必须是非负数字' }
  }
  let total = 0
  for (const n of nums) total = total * 60 + n
  if (total > 7200) return { ok: false, message: '时间超出 0~7200 秒范围' }
  return { ok: true, value: Math.round(total * 100) / 100 }
}

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    question.value = ''
    claim.value = ''
    startText.value = ''
    endText.value = ''
    error.value = null
    createdCount.value = 0
  },
)

async function submit() {
  if (submitting.value) return
  error.value = null
  if (!props.materialId) {
    error.value = '请先选择素材：核验事项必须归属于某个素材'
    return
  }
  if (!question.value.trim()) {
    error.value = '请填写待核验问题'
    return
  }
  if (!claim.value.trim()) {
    error.value = '请填写产品承诺'
    return
  }
  const start = parseSec(startText.value)
  if (!start.ok) {
    error.value = `开始秒：${start.message}`
    return
  }
  const end = parseSec(endText.value)
  if (!end.ok) {
    error.value = `结束秒：${end.message}`
    return
  }
  if ((start.value === null) !== (end.value === null)) {
    error.value = '开始秒与结束秒必须成对填写，或都留空'
    return
  }
  if (start.value !== null && end.value !== null && start.value > end.value) {
    error.value = '开始秒不能晚于结束秒'
    return
  }

  submitting.value = true
  try {
    const created = await createCase({
      materialId: props.materialId,
      question: question.value.trim(),
      claim: claim.value.trim(),
      startSec: start.value,
      endSec: end.value,
    })
    createdCount.value += 1
    emit('created', created)
    ElMessage.success(`已新增核验事项（尚未核验，需人工逐条判定）`)
    if (keepOpen.value) {
      question.value = ''
      claim.value = ''
      startText.value = ''
      endText.value = ''
    } else {
      visible.value = false
    }
  } catch (e) {
    error.value = describeError(e)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="新增核验事项" width="520px" append-to-body>
    <div style="display: flex; flex-direction: column; gap: 12px">
      <div class="lp-note" style="font-size: 11.5px">
        新增后状态为「尚未核验」。回答状态在库里是占位值，<b>不代表视频没有这样说</b>，
        必须在右栏人工改判后才算核验结论。
      </div>

      <div class="lp-field">
        <div class="lp-field-label"><span>归属素材</span></div>
        <div style="font-size: 12.5px">
          <template v-if="activeMaterial">
            {{ activeMaterial.originalName ?? activeMaterial.id }}
            <span class="lp-pill" :class="activeMaterial.builtin ? 'ghost' : 'mint'" style="margin-left: 6px">
              {{ activeMaterial.builtin ? '内置' : '保利威' }}
            </span>
          </template>
          <template v-else>未选择素材</template>
        </div>
        <div v-if="props.materialId && !materialReady" class="lp-note warn" style="margin-top: 6px; font-size: 11.5px">
          素材尚未就绪（当前状态 {{ activeMaterial?.status ?? '未知' }}），后端会拒绝新增，请等待转码完成。
        </div>
      </div>

      <div class="lp-field">
        <div class="lp-field-label"><span>待核验问题</span><em>必填</em></div>
        <el-input v-model="question" maxlength="500" placeholder="例如：单次最多支持导入多少条候选人记录？" />
      </div>

      <div class="lp-field">
        <div class="lp-field-label"><span>产品承诺</span><em>必填</em></div>
        <el-input v-model="claim" maxlength="500" placeholder="例如：支持一次性导入 5000 条" />
      </div>

      <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 10px">
        <div class="lp-field">
          <div class="lp-field-label"><span>开始秒</span></div>
          <el-input v-model="startText" placeholder="留空或 40 / 00:40" />
        </div>
        <div class="lp-field">
          <div class="lp-field-label"><span>结束秒</span></div>
          <el-input v-model="endText" placeholder="留空或 60 / 01:00" />
        </div>
      </div>

      <div v-if="error" class="lp-note warn">{{ error }}</div>

      <el-checkbox v-model="keepOpen" size="small" label="保存后继续新增（连续录入）" />
      <div v-if="createdCount > 0" style="font-size: 11.5px; color: var(--lp-muted)">
        本次已新增 {{ createdCount }} 条
      </div>
    </div>

    <template #footer>
      <div style="display: flex; justify-content: flex-end; gap: 8px">
        <button type="button" class="lp-btn" @click="visible = false">关闭</button>
        <button type="button" class="lp-btn lp-btn-primary" :disabled="submitting" @click="submit">
          {{ submitting ? '保存中…' : '确认新增' }}
        </button>
      </div>
    </template>
  </el-dialog>
</template>