<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createCasesFromCandidates, generateCaseCandidates } from '@/api'
import { ApiError, describeError } from '@/api/client'
import { formatTime } from '@/constants/meta'
import type { AiCandidateResponse, AiCaseCandidate, CaseCandidateItem, Material } from '@/types/api'

const props = defineProps<{
  modelValue: boolean
  materialId: string | null
  material: Material | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'created', count: number): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

/**
 * 五个界面状态，一个都不能并进「没有结果」：
 * idle 没点过、loading 在等模型、error 调用失败、empty 模型看过但确实没有可核验的承诺。
 * 把 empty 和 error 混起来，用户会以为「视频没有承诺」，实际是请求挂了。
 */
type Phase = 'idle' | 'loading' | 'error' | 'empty' | 'ready'

/** 候选在前端是可编辑的副本：勾选、改问题、改承诺、移除，都不回写服务端。 */
interface EditableCandidate extends AiCaseCandidate {
  selected: boolean
  editing: boolean
}

const phase = ref<Phase>('idle')
const response = ref<AiCandidateResponse | null>(null)
const items = ref<EditableCandidate[]>([])
const errorCode = ref<string | null>(null)
const errorText = ref<string | null>(null)
const saveError = ref<string | null>(null)
const saving = ref(false)
const elapsed = ref(0)
let timer: number | undefined

/** 素材没有可用字幕属于「前置条件不满足」，要单独给出路，不能只说「生成失败」。 */
const noSubtitle = computed(
  () => errorCode.value === 'SUBTITLE_UNAVAILABLE' || errorCode.value === 'SMART_SUBTITLE_NOT_READY',
)

const errorHint = computed(() => {
  switch (errorCode.value) {
    case 'SUBTITLE_UNAVAILABLE':
      return '这个素材还没有可用的字幕。候选事项只能从字幕里提取，没有字幕时不会凭空生成。'
    case 'SMART_SUBTITLE_NOT_READY':
      return '云端智能字幕还没有就绪的任务，无法作为提取依据。'
    case 'AI_NOT_CONFIGURED':
      return 'AI 服务未配置：需要后端设置 DEEPSEEK_API_KEY 并重启。这不是「没有候选」，而是功能不可用。'
    case 'AI_TIMEOUT':
      return '模型调用超时，本次没有生成候选，可以重试。'
    case 'AI_RATE_LIMITED':
      return '模型服务当前限流，请稍后重试。'
    case 'AI_UPSTREAM_ERROR':
      return '模型服务返回错误，本次没有生成任何候选。'
    case 'AI_INVALID_RESPONSE':
      return '模型返回的内容无法解析成候选，本次没有生成任何候选。'
    case 'MATERIAL_NOT_READY':
      return '素材还在转码，等状态变成「就绪」后再来生成。'
    case 'NETWORK_ERROR':
      return '浏览器没能连上后端，本次没有生成候选。确认服务已启动后再点重试。'
    default:
      // 5xx 是「请求没走通」，与「模型看完了字幕说没有承诺」是两件完全不同的事，
      // 这里必须明确说清楚，否则用户会误以为视频里没有值得核验的内容。
      return errorCode.value?.startsWith('HTTP_')
        ? `后端返回 ${errorCode.value.slice(5)}，请求没走通，本次没有拿到候选。这不代表字幕里没有承诺。`
        : '本次没有拿到候选，问题出在请求本身，不代表这段字幕里没有承诺。'
  }
})

const selectedCount = computed(() => items.value.filter((item) => item.selected).length)

/** 本次生成是否真的返回过候选（用于区分「模型没找出来」与「用户删光了」）。 */
const hadCandidates = computed(() => (response.value?.candidates.length ?? 0) > 0)

/** 单条的前端预校验：尽量在这里就标出来，不要等提交后整批被拒。 */
function problemOf(item: EditableCandidate): string | null {
  if (!item.question.trim()) return '问题不能为空'
  if (!item.claim.trim()) return '承诺不能为空'
  if (item.question.length > 500) return '问题超过 500 字符'
  if (item.claim.length > 500) return '承诺超过 500 字符'
  if ((item.startSec === null) !== (item.endSec === null)) return '开始与结束秒必须成对填写'
  if (item.startSec !== null && item.endSec !== null && item.startSec > item.endSec) {
    return '开始秒不能大于结束秒'
  }
  return null
}

const blockedCount = computed(
  () => items.value.filter((item) => item.selected && problemOf(item) !== null).length,
)

const canConfirm = computed(() => selectedCount.value > 0 && blockedCount.value === 0 && !saving.value)

const confirmHint = computed(() => {
  if (selectedCount.value === 0) return '至少勾选一条才能入库'
  if (blockedCount.value > 0) return `有 ${blockedCount.value} 条勾选项还有问题，改好或取消勾选后再提交`
  return ''
})

const subtitleLabel = computed(() => {
  const res = response.value
  if (!res) return '—'
  return res.subtitleSource === 'cloud'
    ? '保利威智能字幕'
    : `本地 SRT${res.subtitleFile ? `（${res.subtitleFile}）` : ''}`
})

function startTimer() {
  elapsed.value = 0
  window.clearInterval(timer)
  timer = window.setInterval(() => {
    elapsed.value += 1
  }, 1000)
}

function stopTimer() {
  window.clearInterval(timer)
  timer = undefined
}

function reset() {
  stopTimer()
  phase.value = 'idle'
  response.value = null
  items.value = []
  errorCode.value = null
  errorText.value = null
  saveError.value = null
  saving.value = false
  elapsed.value = 0
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) reset()
  },
)

onBeforeUnmount(stopTimer)

/** 真实调用一次模型。只在用户点击「生成候选」时触发，不会自动跑。 */
async function generate() {
  const materialId = props.materialId
  if (!materialId || phase.value === 'loading') return
  phase.value = 'loading'
  errorCode.value = null
  errorText.value = null
  saveError.value = null
  items.value = []
  response.value = null
  startTimer()
  try {
    const res = await generateCaseCandidates(materialId, 'auto')
    response.value = res
    items.value = res.candidates.map((candidate) => ({
      ...candidate,
      // 疑似重复的默认不勾选：宁可让用户主动选，也不要悄悄把两条一样的问题写进库
      selected: !candidate.duplicate,
      editing: false,
    }))
    phase.value = res.candidates.length > 0 ? 'ready' : 'empty'
  } catch (error) {
    errorCode.value = error instanceof ApiError ? error.code : 'NETWORK_ERROR'
    errorText.value = describeError(error)
    phase.value = 'error'
  } finally {
    stopTimer()
  }
}

function toggleAll(next: boolean) {
  items.value.forEach((item) => {
    // 重复项跟着「全选」一起选上，但用户取消全选时也一起取消；这里不搞特例，避免状态错乱
    item.selected = next
  })
}

function removeItem(id: string) {
  items.value = items.value.filter((item) => item.id !== id)
  if (items.value.length === 0) {
    phase.value = 'empty'
  }
}

function toggleEdit(item: EditableCandidate) {
  item.editing = !item.editing
}

async function confirm() {
  const materialId = props.materialId
  if (!materialId || !canConfirm.value) return
  const payload: CaseCandidateItem[] = items.value
    .filter((item) => item.selected)
    .map((item) => ({
      question: item.question.trim(),
      claim: item.claim.trim(),
      startSec: item.startSec,
      endSec: item.endSec,
    }))
  saving.value = true
  saveError.value = null
  try {
    const result = await createCasesFromCandidates(materialId, payload)
    ElMessage.success(`已写入 ${result.createdCount} 条核验事项，状态为「尚未核验」`)
    emit('created', result.createdCount)
    visible.value = false
  } catch (error) {
    saveError.value = describeError(error)
  } finally {
    saving.value = false
  }
}

function rangeText(item: EditableCandidate): string {
  if (item.startSec === null || item.endSec === null) return '未标注区间'
  return `${formatTime(item.startSec)} - ${formatTime(item.endSec)}`
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="AI 生成候选核验事项"
    width="880px"
    append-to-body
    :close-on-click-modal="!saving"
  >
    <div class="lp-cand">
      <div class="lp-note lp-cand-note">
        AI 只从当前素材的字幕里<b>提出候选问题</b>，不判断视频是否真的做到了。
        勾选后写入的事项同样是「尚未核验」，回答状态为空——AI 起草问题，结论仍然只能由人工给出。
      </div>

      <div class="lp-cand-meta">
        <span class="k">素材</span>
        <span class="v">{{ material?.originalName ?? material?.id ?? '未选择素材' }}</span>
        <span class="k">字幕</span>
        <span class="v">{{ subtitleLabel }}</span>
        <span class="k">候选</span>
        <span class="v">{{ phase === 'ready' ? `${items.length} 条` : '—' }}</span>
      </div>

      <!-- 1. 未生成 -->
      <div v-if="phase === 'idle'" class="lp-cand-state">
        <div class="t">还没有生成</div>
        <div class="d">
          点下方「生成候选」，会读取该素材整段字幕并请求一次模型，通常在 15–40 秒内返回。
          生成结果只是预览，不会自动写入数据库。
        </div>
      </div>

      <!-- 2. 生成中 -->
      <div v-else-if="phase === 'loading'" class="lp-cand-state is-busy">
        <span class="lp-ai-spinner"></span>
        <div>
          <div class="t">正在读取字幕并提取候选…已等待 {{ elapsed }} 秒</div>
          <div class="d">整段字幕需要一次性提交给模型，比单条核验更慢一些。这一步不会写入任何数据。</div>
        </div>
      </div>

      <!-- 3. 失败 / 无可用字幕 -->
      <div v-else-if="phase === 'error'" class="lp-cand-state">
        <div style="display: flex; align-items: center; gap: 8px">
          <span class="lp-pill warn">{{ noSubtitle ? '无可用字幕' : '生成失败' }}</span>
          <span class="code">{{ errorCode }}</span>
        </div>
        <div class="t" style="margin-top: 8px">{{ errorHint }}</div>
        <div class="d">{{ errorText }}</div>
        <div v-if="noSubtitle" class="d">
          可去「视频与素材」页上传一份 SRT，或为这个素材创建保利威智能字幕任务；
          准备好字幕后再回到这里重新生成。
        </div>
        <div v-else-if="errorCode === 'AI_NOT_CONFIGURED'" class="d">
          可先手动新增或从 CSV 导入核验事项，它们不依赖 AI。
        </div>
      </div>

      <!-- 4. 没有候选：区分「模型确实没找出来」与「用户自己把候选删光了」，后者不能说成前者 -->
      <div v-else-if="phase === 'empty'" class="lp-cand-state">
        <template v-if="hadCandidates">
          <div class="t">候选已全部移除</div>
          <div class="d">
            本次生成过 {{ response?.candidates.length ?? 0 }} 条候选，都已逐条移除，因此没有可入库的内容。
            模型并没有重新判断过这段字幕。
          </div>
          <div class="d">点「重新生成」会再请求一次模型；也可以改用「手动新增一条」自己写。</div>
        </template>
        <template v-else>
          <div class="t">字幕里没有找到可核验的产品承诺</div>
          <div class="d">
            模型看完了本次提交的 {{ response?.cuesUsed ?? 0 }} 条字幕，没有找出值得单独核验的承诺。
            这不等于视频没有价值，可能只是这段演示以操作演示为主、没有明确说出能力或数量。
          </div>
          <div class="d">可以改用「手动新增一条」逐条录入，或换一份更完整的字幕后重试。</div>
        </template>
      </div>

      <!-- 5. 有候选 -->
      <template v-else>
        <div class="lp-cand-stats">
          <span class="lp-pill mint">候选 {{ items.length }} 条</span>
          <span class="lp-pill" :class="(response?.duplicateCount ?? 0) > 0 ? 'warn' : 'ghost'">
            疑似重复 {{ response?.duplicateCount ?? 0 }} 条
          </span>
          <span class="lp-pill ghost">已选 {{ selectedCount }} 条</span>
          <span class="lp-pill ghost">字幕 {{ response?.cuesUsed ?? 0 }} 条</span>
          <span class="lp-pill ghost">{{ ((response?.elapsedMs ?? 0) / 1000).toFixed(1) }}s</span>
          <span v-if="response?.truncated" class="lp-pill warn">字幕过长，已抽样</span>
          <span class="grow"></span>
          <button type="button" class="lp-btn lp-btn-text" @click="toggleAll(selectedCount < items.length)">
            {{ selectedCount < items.length ? '全选' : '全不选' }}
          </button>
        </div>

        <div v-if="response?.notes.length" class="lp-note lp-cand-notes">
          <div v-for="(note, index) in response.notes" :key="index">· {{ note }}</div>
        </div>

        <div class="lp-cand-list">
          <div
            v-for="(item, index) in items"
            :key="item.id"
            class="lp-cand-item"
            :class="{ 'is-picked': item.selected, 'is-dup': item.duplicate }"
          >
            <div class="head">
              <el-checkbox v-model="item.selected" />
              <span class="idx">{{ index + 1 }}</span>
              <span class="time">{{ rangeText(item) }}</span>
              <span v-if="item.duplicate" class="lp-pill warn">疑似重复</span>
              <span class="grow"></span>
              <button type="button" class="lp-btn lp-btn-text" @click="toggleEdit(item)">
                {{ item.editing ? '完成' : '编辑' }}
              </button>
              <button type="button" class="lp-btn lp-btn-text danger" @click="removeItem(item.id)">
                移除
              </button>
            </div>

            <template v-if="!item.editing">
              <div class="q">{{ item.question }}</div>
              <div class="c">{{ item.claim }}</div>
            </template>
            <div v-else class="edit">
              <label>问题</label>
              <el-input v-model="item.question" size="small" maxlength="500" show-word-limit />
              <label>承诺</label>
              <el-input
                v-model="item.claim"
                type="textarea"
                :rows="2"
                size="small"
                maxlength="500"
                show-word-limit
              />
            </div>

            <div v-if="item.quote" class="quote">原话：{{ item.quote }}</div>

            <div v-if="item.duplicate" class="dup">
              {{
                item.duplicateOfIndex
                  ? `与本批第 ${item.duplicateOfIndex} 条是同一个问题`
                  : `该素材下已有相同问题的事项${item.duplicateOf ? `（${item.duplicateOf}）` : ''}`
              }}
            </div>

            <div v-if="item.selected && problemOf(item)" class="problem">{{ problemOf(item) }}</div>
          </div>
        </div>

        <div v-if="saveError" class="lp-note warn">{{ saveError }}</div>
      </template>
    </div>

    <template #footer>
      <div class="lp-cand-foot">
        <span class="left">
          <!-- 按钮被禁用时 title 提示在很多浏览器里不弹出，拦截原因必须写在可见位置 -->
          <template v-if="phase === 'ready' && blockedCount > 0">
            <span class="warn">{{ confirmHint }}</span>
          </template>
          <template v-else-if="phase === 'ready'">
            已选 {{ selectedCount }} 条 · 写入后状态为「尚未核验」
          </template>
          <template v-else-if="confirmHint">{{ confirmHint }}</template>
        </span>
        <button type="button" class="lp-btn" @click="visible = false">关闭</button>
        <button
          v-if="phase === 'idle' || phase === 'error' || phase === 'empty'"
          type="button"
          class="lp-btn lp-btn-primary"
          :disabled="!materialId"
          @click="generate"
        >
          {{ phase === 'idle' ? '生成候选' : '重新生成' }}
        </button>
        <button
          v-else-if="phase === 'ready'"
          type="button"
          class="lp-btn lp-btn-primary"
          :disabled="!canConfirm"
          :title="confirmHint"
          @click="confirm"
        >
          {{ saving ? '写入中…' : `确认入库（${selectedCount} 条）` }}
        </button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.lp-cand {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.lp-cand-note {
  font-size: 11.5px;
  line-height: 1.6;
}
.lp-cand-meta {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) 40px minmax(0, 1fr) 40px 60px;
  gap: 6px 8px;
  align-items: center;
  font-size: 12px;
  padding: 8px 10px;
  border: 1px solid var(--lp-border, #e5ece9);
  border-radius: 9px;
  background: #fbfdfc;
}
.lp-cand-meta .k {
  color: var(--lp-muted, #71817c);
}
.lp-cand-meta .v {
  color: var(--lp-ink, #081a26);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.lp-cand-state {
  border: 1px dashed var(--lp-border-strong, #cfdcd7);
  border-radius: 10px;
  padding: 16px;
  background: #fff;
}
.lp-cand-state.is-busy {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  border-style: solid;
  background: var(--lp-mint-weak, #e8fbf4);
}
.lp-cand-state .t {
  font-size: 13px;
  font-weight: 600;
  color: var(--lp-ink, #081a26);
}
.lp-cand-state .d {
  margin-top: 5px;
  font-size: 11.5px;
  color: var(--lp-muted, #71817c);
  line-height: 1.6;
}
.lp-cand-state .code {
  font-size: 11px;
  color: var(--lp-muted-soft, #8b9a95);
  font-family: 'JetBrains Mono', Consolas, monospace;
}
.lp-cand-stats {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.lp-cand-stats .grow {
  flex: 1 1 auto;
}
.lp-cand-notes {
  font-size: 11px;
  line-height: 1.6;
  color: var(--lp-muted, #71817c);
}
.lp-cand-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 400px;
  overflow-y: auto;
  padding-right: 2px;
}
.lp-cand-item {
  border: 1px solid var(--lp-border, #e5ece9);
  border-radius: 9px;
  padding: 9px 11px 10px;
  background: #fff;
}
.lp-cand-item.is-picked {
  border-color: var(--lp-mint, #00e5a8);
  background: #f7fdfa;
}
.lp-cand-item.is-dup {
  background: #fffdf7;
}
.lp-cand-item .head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.lp-cand-item .head .grow {
  flex: 1 1 auto;
}
.lp-cand-item .idx {
  font-size: 11px;
  font-weight: 600;
  color: var(--lp-muted, #71817c);
  min-width: 14px;
}
.lp-cand-item .time {
  font-size: 11px;
  color: var(--lp-muted, #71817c);
  font-family: 'JetBrains Mono', Consolas, monospace;
}
.lp-cand-item .q {
  margin-top: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--lp-ink, #081a26);
  line-height: 1.5;
}
.lp-cand-item .c {
  margin-top: 3px;
  font-size: 12px;
  color: var(--lp-ink-soft, #23414f);
  line-height: 1.55;
}
.lp-cand-item .quote {
  margin-top: 6px;
  padding: 6px 8px;
  border-left: 2px solid var(--lp-border-strong, #cfdcd7);
  background: #f7faf9;
  border-radius: 0 6px 6px 0;
  font-size: 11.5px;
  color: var(--lp-muted, #71817c);
  line-height: 1.55;
}
.lp-cand-item .dup {
  margin-top: 6px;
  font-size: 11px;
  color: #b4700f;
}
.lp-cand-item .problem {
  margin-top: 6px;
  font-size: 11px;
  color: #c0392b;
}
.lp-cand-item .edit {
  margin-top: 6px;
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr);
  gap: 6px 8px;
  align-items: start;
}
.lp-cand-item .edit label {
  font-size: 11.5px;
  color: var(--lp-muted, #71817c);
  padding-top: 5px;
}
.lp-cand-foot {
  display: flex;
  align-items: center;
  gap: 8px;
}
.lp-cand-foot .left {
  flex: 1 1 auto;
  text-align: left;
  font-size: 11.5px;
  color: var(--lp-muted, #71817c);
}
.lp-cand-foot .left .warn {
  color: #b26a00;
}
.lp-btn-text.danger {
  color: #c0392b;
}
</style>
