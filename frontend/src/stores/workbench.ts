import { defineStore } from 'pinia'
import { computed, reactive, ref } from 'vue'
import { ApiError, describeError } from '@/api/client'
import {
  createCase,
  deleteCase,
  fetchCase,
  fetchCases,
  fetchDatasets,
  fetchMaterialPlay,
  fetchMaterials,
  fetchSmartSubtitleCues,
  fetchSmartSubtitleTasks,
  fetchSubtitles,
  patchCase,
} from '@/api'
import type {
  CaseCreatePayload,
  CaseDetail,
  CaseListItem,
  CasePatchPayload,
  Dataset,
  Material,
  SmartSubtitleTask,
  SubtitleResponse,
  SubtitleSource,
  UnifiedCue,
} from '@/types/api'

/**
 * 工作台数据源：全部来自 Spring Boot（8080），没有任何本地兜底假数据。
 * 左栏列表与右栏编辑面板共用同一份数据：右栏保存成功后，左栏立即同步。
 *
 * <p>字幕有两个来源（本地 SRT / 保利威智能字幕），cue 结构一致，
 * 统一在本 store 里补齐 `source` 标注，视图层不再按端点分支。
 *
 * <p>素材维度：{@code materialId} 为空表示「全部素材」，非空则左栏只显示该素材下的核验事项。
 * 播放地址按素材解析：云端素材走 play 接口取保利威 CDN 直链，
 * 内置素材继续走 /media/{fileName}，两条轨道互不替代。
 */
export const useWorkbenchStore = defineStore('workbench', () => {
  const datasets = ref<Dataset[]>([])
  const cases = ref<CaseListItem[]>([])
  const activeCaseId = ref<string | null>(null)
  const detail = ref<CaseDetail | null>(null)

  /** 素材列表与当前筛选素材；materialId 为 null 表示不限素材 */
  const materials = ref<Material[]>([])
  const materialId = ref<string | null>(null)
  /** 云端素材的保利威播放地址；内置素材为 null（走 /media） */
  const playUrl = ref<string | null>(null)
  const playLoading = ref(false)
  const playError = ref<string | null>(null)

  const cues = ref<UnifiedCue[]>([])
  const subtitle = ref<SubtitleResponse | null>(null)
  const subtitleError = ref<string | null>(null)
  const subtitleSource = ref<SubtitleSource>('local')
  /** 当前视频已就绪的云端字幕任务；null 表示还没有（切换云端前必须先在素材页准备） */
  const cloudTask = ref<SmartSubtitleTask | null>(null)
  const cloudSwitching = ref(false)

  const errors = reactive<{ datasets: string | null; cases: string | null; detail: string | null }>({
    datasets: null,
    cases: null,
    detail: null,
  })
  const loading = reactive({ cases: false, detail: false, subtitles: false, materials: false })
  const saving = ref(false)
  const lastSavedAt = ref<string | null>(null)

  /**
   * 详情加载失败时的错误码（{@code CASE_NOT_FOUND} / {@code NETWORK_ERROR} / null）。
   *
   * <p>只存文案没法区分「这条事项不存在」和「后端根本没连上」：
   * 前者要清掉地址栏里失效的 {@code ?case=} 并回退，后者必须保留 URL 等用户重试。
   */
  const detailErrorCode = ref<string | null>(null)

  const activeCase = computed(() => detail.value)
  const videoName = computed(() => detail.value?.video ?? subtitle.value?.video ?? null)

  /** 当前筛选素材对象；null 表示「全部素材」 */
  const activeMaterial = computed(() =>
    materialId.value ? materials.value.find((item) => item.id === materialId.value) ?? null : null,
  )

  function syncListItem(updated: CaseDetail) {
    const index = cases.value.findIndex((item) => item.id === updated.id)
    if (index >= 0) {
      cases.value[index] = {
        ...cases.value[index],
        reviewStatus: updated.reviewStatus,
        answerStatus: updated.answerStatus,
        evidenceType: updated.evidenceType,
        evidenceSufficiency: updated.evidenceSufficiency,
        updatedAt: updated.updatedAt,
      }
    }
  }

  async function loadMaterials() {
    loading.materials = true
    try {
      materials.value = await fetchMaterials()
    } catch {
      // 素材列表失败不阻塞工作台：用例与字幕仍可用，错误由素材页单独展示
      materials.value = []
    } finally {
      loading.materials = false
    }
  }

  /** 加载素材库（工作台页头的素材下拉与播放地址解析都依赖它） */
  async function ensureMaterials() {
    if (materials.value.length === 0) {
      await loadMaterials()
    }
    return materials.value
  }

  /**
   * 切换当前素材范围并重载左栏。
   *
   * @param id 素材 id；null 表示「全部素材」
   */
  async function setMaterialScope(id: string | null, ds = 'cf') {
    materialId.value = id
    activeCaseId.value = null
    detail.value = null
    await loadWorkspace(ds)
  }

  /**
   * 按当前素材解析播放源。
   *
   * <p>内置素材用 fileName 走 /media；云端素材取 play 接口的 CDN 直链。
   * 取不到地址时如实报错并返回 null，绝不用别的素材顶替。
   */
  async function resolvePlaySource(targetMaterialId: string | null, fileName: string | null): Promise<string | null> {
    playError.value = null
    if (!targetMaterialId) {
      return fileName ? `/media/${encodeURIComponent(fileName)}` : null
    }
    const material = materials.value.find((item) => item.id === targetMaterialId)
    if (!material) {
      return fileName ? `/media/${encodeURIComponent(fileName)}` : null
    }
    if (material.storage === 'local') {
      const local = material.fileName ?? fileName
      return local ? `/media/${encodeURIComponent(local)}` : null
    }
    playLoading.value = true
    try {
      const play = await fetchMaterialPlay(material.id)
      playUrl.value = play.playUrl
      return play.playUrl
    } catch (error) {
      playUrl.value = null
      playError.value = describeError(error)
      return null
    } finally {
      playLoading.value = false
    }
  }

  async function loadWorkspace(ds = 'cf') {
    loading.cases = true
    errors.cases = null
    try {
      const [dsList, caseList] = await Promise.all([
        fetchDatasets(),
        fetchCases(ds, materialId.value),
      ])
      datasets.value = dsList
      cases.value = caseList
      errors.datasets = null
      if (!activeCaseId.value && caseList.length > 0) {
        await selectCase(caseList[0].id)
      } else if (activeCaseId.value && !caseList.some((c) => c.id === activeCaseId.value)) {
        await selectCase(caseList.length > 0 ? caseList[0].id : null)
      } else if (caseList.length === 0) {
        await selectCase(null)
        // 这个素材下还没有事项时也要把字幕读出来：空列表是用户要录第一条事项的时刻，
        // 看不到这段视频讲了什么就只能凭空写承诺。字幕按素材解析，与选中事项后同一口径。
        await loadSubtitlesForMaterial()
      }
    } catch (error) {
      errors.cases = describeError(error)
    } finally {
      loading.cases = false
    }
  }

  async function selectCase(id: string | null) {
    if (id === null) {
      activeCaseId.value = null
      detail.value = null
      return
    }
    activeCaseId.value = id
    await loadDetail(id)
  }

  async function loadDetail(id: string) {
    loading.detail = true
    errors.detail = null
    detailErrorCode.value = null
    try {
      const data = await fetchCase(id)
      detail.value = data
      // 素材范围跟随选中用例，但只在用户没有显式筛选时才收敛（避免下拉跳变）
      if (!materialId.value && data.materialId) {
        materialId.value = data.materialId
      }
      if (data.video) {
        await loadSubtitles(data.video)
      }
    } catch (error) {
      errors.detail = describeError(error)
      detailErrorCode.value = error instanceof ApiError ? error.code : null
      detail.value = null
    } finally {
      loading.detail = false
    }
  }

  /** 没有选中事项时，按当前素材解析字幕（video 用 fileName，云端素材用 id）。 */
  async function loadSubtitlesForMaterial() {
    const material = materialId.value
      ? materials.value.find((item) => item.id === materialId.value) ?? null
      : null
    const video = material ? material.fileName ?? material.id : null
    if (!video) {
      subtitle.value = null
      cues.value = []
      return
    }
    await loadSubtitles(video)
  }

  /**
   * 按当前来源加载字幕。
   *
   * <p>云端来源不会自己创建任务：创建要消耗保利威资源点，属于素材页的显式动作。
   * 没有就绪任务时如实报错并保持空列表，绝不用本地字幕顶替。
   */
  async function loadSubtitles(video: string, source: SubtitleSource = subtitleSource.value) {
    loading.subtitles = true
    subtitleError.value = null
    try {
      if (source === 'cloud') {
        const tasks = await fetchSmartSubtitleTasks(video)
        const ready = tasks.find((task) => task.status === 'ready' && task.taskId) ?? null
        cloudTask.value = ready
        if (!ready || !ready.taskId) {
          subtitle.value = null
          cues.value = []
          subtitleError.value = '该视频还没有就绪的智能字幕任务，请先在「素材与字幕」页创建或查询任务'
          return
        }
        const cloud = await fetchSmartSubtitleCues(ready.taskId)
        subtitle.value = null
        cues.value = cloud.cues.map((cue) => ({ ...cue, source: 'cloud' as const }))
        return
      }
      const data = await fetchSubtitles(video)
      subtitle.value = data
      cues.value = data.cues.map((cue) => ({ ...cue, source: 'local' as const }))
    } catch (error) {
      subtitle.value = null
      cues.value = []
      subtitleError.value = describeError(error)
    } finally {
      loading.subtitles = false
    }
  }

  /** 切换字幕来源：只重拉字幕，不动用例数据。 */
  async function setSubtitleSource(source: SubtitleSource) {
    if (source === subtitleSource.value) return
    cloudSwitching.value = true
    subtitleSource.value = source
    try {
      const video = videoName.value
      if (video) {
        await loadSubtitles(video, source)
      }
    } finally {
      cloudSwitching.value = false
    }
  }

  /** 保存改判：等待后端响应，失败抛出由调用方提示，不乐观更新。 */
  async function saveCase(payload: CasePatchPayload): Promise<CaseDetail> {
    const id = activeCaseId.value
    if (!id) throw new Error('未选中用例')
    saving.value = true
    try {
      const updated = await patchCase(id, payload)
      // 请求在途时用户可能已切到别的事项：此时把旧用例的详情写回会覆盖新事项的编辑态。
      // 左栏状态仍然要同步（那是这个用例自己的属性），详情只在仍停留于该用例时才写回。
      syncListItem(updated)
      if (activeCaseId.value === id) {
        detail.value = updated
        lastSavedAt.value = updated.updatedAt
      }
      return updated
    } finally {
      saving.value = false
    }
  }

  /**
   * 手动新增核验事项并选中它。
   *
   * <p>新增后重新拉取列表，让左栏的顺序与后端一致（后端按 startSec 排序），
   * 不在前端拼一条假数据顶替。
   */
  async function addCase(payload: CaseCreatePayload): Promise<CaseDetail> {
    const created = await createCase(payload)
    cases.value = await fetchCases('cf', materialId.value)
    await selectCase(created.id)
    return created
  }

  /** 删除核验事项；有挂接待办时后端返回 409，由调用方决定是否带 withTodos。 */
  async function removeCase(id: string, withTodos = false): Promise<void> {
    await deleteCase(id, withTodos)
    cases.value = await fetchCases('cf', materialId.value)
    if (activeCaseId.value === id) {
      await selectCase(cases.value.length > 0 ? cases.value[0].id : null)
    }
  }

  /**
   * 导入完成后刷新左栏（导入的写入由后端事务保证，前端只重读）。
   *
   * @param options.keepActive 为 true 时即使当前事项已不在列表中也不自动切换。
   *   自动切换会覆盖右栏正在编辑的草稿，而 Store 无法判断 {@code dirty}；
   *   需要切换时由视图层在确认后再显式调用 {@code selectCase}。
   */
  async function reloadCases(options: { keepActive?: boolean } = {}) {
    cases.value = await fetchCases('cf', materialId.value)
    if (options.keepActive) return
    if (activeCaseId.value && !cases.value.some((item) => item.id === activeCaseId.value)) {
      await selectCase(cases.value.length > 0 ? cases.value[0].id : null)
    }
  }

  return {
    datasets,
    cases,
    activeCaseId,
    activeCase,
    detail,
    materials,
    materialId,
    activeMaterial,
    playUrl,
    playLoading,
    playError,
    cues,
    subtitle,
    subtitleError,
    subtitleSource,
    cloudTask,
    cloudSwitching,
    errors,
    detailErrorCode,
    loading,
    saving,
    lastSavedAt,
    videoName,
    loadWorkspace,
    selectCase,
    loadDetail,
    loadSubtitles,
    setSubtitleSource,
    saveCase,
    syncListItem,
    loadMaterials,
    ensureMaterials,
    setMaterialScope,
    resolvePlaySource,
    addCase,
    removeCase,
    reloadCases,
  }
})
