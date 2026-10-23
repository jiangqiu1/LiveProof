<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createTodo, updateTodo } from '@/api'
import { describeError } from '@/api/client'
import type { CaseListItem, Todo, TodoStatus } from '@/types/api'
import { TODO_STATUS_LABEL, TODO_STATUS_OPTIONS } from '@/constants/meta'

const props = defineProps<{
  modelValue: boolean
  todo: Todo | null
  cases: CaseListItem[]
  presetCaseId?: string | null
  defaultTitle?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'saved', todo: Todo): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const caseId = ref('')
const title = ref('')
const detail = ref('')
const status = ref<TodoStatus>('open')
const submitting = ref(false)
const [savingLabel] = ['保存中…']

const isEdit = computed(() => props.todo !== null)

const caseOptions = computed(() =>
  props.cases.map((item) => ({ value: item.id, label: item.question ?? item.id })),
)

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    if (props.todo) {
      caseId.value = props.todo.caseId
      title.value = props.todo.title
      detail.value = props.todo.detail ?? ''
      status.value = props.todo.status
    } else {
      caseId.value = props.presetCaseId ?? props.cases[0]?.id ?? ''
      title.value = props.defaultTitle ?? ''
      detail.value = ''
      status.value = 'open'
    }
  },
)

async function submit() {
  if (submitting.value) return
  if (!isEdit.value && !caseId.value) {
    ElMessage.error('请选择关联用例')
    return
  }
  if (!title.value.trim()) {
    ElMessage.error('请填写待办标题')
    return
  }
  submitting.value = true
  try {
    const saved = props.todo
      ? await updateTodo(props.todo.id, {
          title: title.value.trim(),
          detail: detail.value.trim() || null,
          status: status.value,
        })
      : await createTodo({
          caseId: caseId.value,
          title: title.value.trim(),
          detail: detail.value.trim() || null,
        })
    ElMessage.success(isEdit.value ? '待办已更新' : '待办已创建')
    emit('saved', saved)
    visible.value = false
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="isEdit ? '编辑待办' : '新建待办'" width="460px" append-to-body>
    <div style="display: flex; flex-direction: column; gap: 12px">
      <div class="lp-field">
        <div class="lp-field-label"><span>关联用例</span></div>
        <el-select v-model="caseId" :disabled="isEdit" filterable style="width: 100%" placeholder="选择关联用例">
          <el-option v-for="option in caseOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
      </div>
      <div class="lp-field">
        <div class="lp-field-label"><span>标题</span></div>
        <el-input v-model="title" maxlength="255" placeholder="例如：补一次 5000 条导入演示" />
      </div>
      <div class="lp-field">
        <div class="lp-field-label"><span>说明</span></div>
        <el-input
          v-model="detail"
          type="textarea"
          :rows="3"
          maxlength="5000"
          placeholder="补充需要补录的演示内容、判定标准等"
        />
      </div>
      <div v-if="isEdit" class="lp-field">
        <div class="lp-field-label"><span>状态</span></div>
        <el-select v-model="status" style="width: 100%">
          <el-option
            v-for="option in TODO_STATUS_OPTIONS"
            :key="option.value"
            :label="TODO_STATUS_LABEL[option.value]"
            :value="option.value"
          />
        </el-select>
      </div>
    </div>
    <template #footer>
      <div style="display: flex; justify-content: flex-end; gap: 8px">
        <button type="button" class="lp-btn" @click="visible = false">取消</button>
        <button type="button" class="lp-btn lp-btn-primary" :disabled="submitting" @click="submit">
          {{ submitting ? savingLabel : '保存' }}
        </button>
      </div>
    </template>
  </el-dialog>
</template>
