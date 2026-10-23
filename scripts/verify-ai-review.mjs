// AI 辅助核验 + 复盘报告的真实验证脚本
//
// 用途：配置好 DEEPSEEK_API_KEY 并重启后端后，跑一轮真实模型调用并做结构核对。
// 注意：本脚本会真实消耗模型额度（1 次核验 + 1 次报告归纳），不要反复运行。
//
// 用法：node scripts/verify-ai-review.mjs [caseId]
// 输出：docs/assets/ai/verify-ai-run.json（含模型返回与核对结论，不含任何密钥）
//
// 核对项：
//   1. 建议不含 reviewStatus，且未修改数据库中的任何已保存字段
//   2. 枚举取值合法
//   3. 时间点落在视频时长内，且能对应到提交过的字幕/关键帧
//   4. 报告统计与 GET /api/cases 的真实计数一致
//   5. 未检查事项仍为未检查

import { writeFileSync, mkdirSync } from 'node:fs'
import { join } from 'node:path'

const API = process.env.LIVEPROOF_API || 'http://127.0.0.1:8080'
const CASE_ID = process.argv[2] || 'cf-import-3-records'
const OUT = join(import.meta.dirname, '..', 'docs', 'assets', 'ai')

const ANSWER = ['answered', 'partially_answered', 'not_found']
const TYPE = ['verbal_claim', 'specification_display', 'operational_evidence', 'unknown']
const SUFFICIENCY = ['not_checked', 'insufficient', 'sufficient']

const problems = []

async function json(path, init) {
  const res = await fetch(API + path, init)
  const text = await res.text()
  let body = null
  try {
    body = text ? JSON.parse(text) : null
  } catch {
    body = { raw: text.slice(0, 400) }
  }
  return { status: res.status, body }
}

function check(condition, message) {
  if (!condition) problems.push(message)
}

async function main() {
  mkdirSync(OUT, { recursive: true })
  const status = await json('/api/ai/status')
  console.log('AI 状态：', JSON.stringify(status.body))

  if (!status.body?.configured) {
    const summary = {
      ranAt: new Date().toISOString(),
      configured: false,
      conclusion: '未配置 DEEPSEEK_API_KEY，未执行真实模型调用（这是部署状态，不是功能失败）',
      howToVerify: '在后端环境变量（或 .env.local）中设置 DEEPSEEK_API_KEY 后重启，再执行本脚本',
      aiStatus: status.body,
    }
    writeFileSync(join(OUT, 'verify-ai-run.json'), JSON.stringify(summary, null, 2), 'utf8')
    console.log('\n未配置模型密钥，已跳过真实调用。' + summary.howToVerify)
    return
  }

  // ---- 1. 分析前快照 ----
  const before = await json(`/api/cases/${CASE_ID}`)
  check(before.status === 200, `用例详情读取失败：HTTP ${before.status}`)

  // ---- 2. 真实调用 AI 核验 ----
  const started = Date.now()
  const review = await json(`/api/cases/${CASE_ID}/ai-review`, { method: 'POST' })
  const elapsedMs = Date.now() - started
  console.log(`AI 核验 HTTP ${review.status}，耗时 ${elapsedMs}ms`)
  check(review.status === 200, `AI 核验未成功：HTTP ${review.status} ${JSON.stringify(review.body)?.slice(0, 300)}`)

  const suggestion = review.body?.suggestion
  const context = review.body?.context
  if (suggestion) {
    check(ANSWER.includes(suggestion.answerStatus), `answerStatus 非法：${suggestion.answerStatus}`)
    check(TYPE.includes(suggestion.evidenceType), `evidenceType 非法：${suggestion.evidenceType}`)
    check(
      SUFFICIENCY.includes(suggestion.evidenceSufficiency),
      `evidenceSufficiency 非法：${suggestion.evidenceSufficiency}`,
    )
    check(!('reviewStatus' in suggestion), '响应中不应出现 reviewStatus')

    const duration = context?.durationSec ?? 0
    for (const point of suggestion.suggestedTimePoints ?? []) {
      check(typeof point === 'number', `时间点不是数值：${point}`)
      check(point >= 0 && (duration === 0 || point <= duration + 0.5), `时间点越界：${point} > ${duration}`)
      const aligned =
        (context?.cuesUsed ?? []).some((cue) => point >= cue.startSec - 2 && point <= cue.endSec + 2) ||
        (context?.frames ?? []).some((frame) => Math.abs(frame.sec - point) <= 2)
      check(aligned, `时间点 ${point} 未对应任何已提交的字幕或关键帧`)
    }
    console.log(
      `建议：${suggestion.answerStatus} / ${suggestion.evidenceType} / ${suggestion.evidenceSufficiency}；` +
        `证据 ${suggestion.evidenceItems?.length ?? 0} 条；时间点 ${(suggestion.suggestedTimePoints ?? []).join(', ')}`,
    )
    console.log(
      `上下文：字幕 ${context?.cuesUsed?.length}/${context?.totalCueCount} 条，关键帧 ${context?.frames?.length ?? 0} 帧，` +
        `画面可用=${context?.visionUsed}，模型=${context?.model}`,
    )
  }

  // ---- 3. 数据库不应被改动 ----
  const after = await json(`/api/cases/${CASE_ID}`)
  for (const key of [
    'reviewStatus',
    'answerStatus',
    'evidenceType',
    'evidenceSufficiency',
    'reasoning',
    'limitations',
  ]) {
    check(before.body?.[key] === after.body?.[key], `AI 核验改动了数据库字段 ${key}`)
  }
  check(
    JSON.stringify(before.body?.timePoints ?? []) === JSON.stringify(after.body?.timePoints ?? []),
    'AI 核验改动了 timePoints',
  )

  // ---- 4. 报告统计核对 ----
  const report = await json('/api/reports/review?ds=cf', { method: 'POST' })
  check(report.status === 200, `报告生成失败：HTTP ${report.status}`)
  const cases = await json('/api/cases?ds=cf')
  if (Array.isArray(cases.body) && report.body?.stats) {
    const stats = report.body.stats
    check(stats.total === cases.body.length, `报告总数 ${stats.total} ≠ 实际 ${cases.body.length}`)
    const reviewed = cases.body.filter((c) => c.reviewStatus === 'reviewed').length
    const unreviewed = cases.body.filter((c) => c.reviewStatus === 'unreviewed').length
    check(stats.reviewed === reviewed, `报告已核验 ${stats.reviewed} ≠ 实际 ${reviewed}`)
    check(stats.unreviewed === unreviewed, `报告未检查 ${stats.unreviewed} ≠ 实际 ${unreviewed}`)
    const markdown = report.body.markdown ?? ''
    check(
      markdown.includes(`### 6.1 尚未人工检查（${unreviewed} 项）`),
      '报告「尚未检查」小节数量与数据库不一致',
    )
    console.log(`报告：${markdown.length} 字符，AI 归纳=${report.body.aiUsed}，统计 total=${stats.total}`)
  }

  const summary = {
    ranAt: new Date().toISOString(),
    configured: true,
    caseId: CASE_ID,
    elapsedMs,
    aiStatus: status.body,
    suggestion: suggestion ?? null,
    context: context
      ? {
          video: context.video,
          durationSec: context.durationSec,
          cuesUsed: context.cuesUsed?.length,
          frames: context.frames?.length,
          visionUsed: context.visionUsed,
          frameNote: context.frameNote,
          model: context.model,
          promptTokens: context.promptTokens,
          completionTokens: context.completionTokens,
        }
      : null,
    normalizationNotes: review.body?.normalizationNotes ?? [],
    report: {
      aiUsed: report.body?.aiUsed,
      stats: report.body?.stats,
      notes: report.body?.notes,
      markdownChars: report.body?.markdown?.length ?? 0,
      sample: (report.body?.markdown ?? '').slice(0, 1200),
    },
    problems,
    conclusion: problems.length === 0 ? '结构与一致性核对通过（不代表模型判断一定正确，需人工抽查）' : '存在问题',
  }
  writeFileSync(join(OUT, 'verify-ai-run.json'), JSON.stringify(summary, null, 2), 'utf8')

  if (problems.length > 0) {
    console.error('\n发现问题：')
    problems.forEach((p) => console.error(' - ' + p))
    process.exitCode = 1
    return
  }
  console.log('\n核对通过：结构合法、时间点有出处、数据库未被改动、报告统计一致。')
  console.log('提醒：这只说明链路与校验正确，模型判断仍需人工对照视频抽查。')
}

main().catch((e) => {
  console.error('验证脚本失败：', e.message)
  process.exit(1)
})
