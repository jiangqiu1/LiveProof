/**
 * 阶段二真实联调校验：保利威智能字幕后端接入。
 *
 * 前置：
 *   1) 后端已在 8080 运行（scripts/run-backend.sh --server.port=8080）
 *   2) liveproof 库已执行 V6 迁移
 *   3) 阶段一真实任务 taskId=522143 仍存在于保利威账号
 *
 * 本脚本只使用「只读查询」与「登记既有任务」，不会创建新任务（不会消耗资源点）。
 *
 * 校验点：
 *   - 接入状态与 VID 映射（不回显凭据）
 *   - 既有任务可登记、幂等、状态 ready
 *   - 毫秒 -> 秒的换算与阶段一审计记录逐条一致（29 条）
 *   - 时间窗按区间重叠筛选
 *   - 再次创建同视频任务返回 reused=true（未真正创建）
 *   - 本地 SRT 接口（12 条）行为不变
 *   - 响应体中不出现任何密钥/签名
 */
import fs from 'node:fs'
import path from 'node:path'

const BASE = process.env.LIVEPROOF_API || 'http://127.0.0.1:8080'
const VIDEO = 'candidateflow-demo-nosub.mp4'
const TASK_ID = process.env.POLYV_TASK_ID || '522143'
const RESULT_FILE = path.join(process.cwd(), '_verify', 'smart-subtitle-result.json')

const problems = []
const steps = []

function check(ok, label, detail) {
  steps.push({ label, ok, detail })
  if (!ok) problems.push(`${label} :: ${detail}`)
}

async function call(method, url, body) {
  const res = await fetch(BASE + url, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  })
  const text = await res.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    /* 非 JSON 时保留原文用于报错 */
  }
  return { status: res.status, json, text }
}

const secrets = Object.fromEntries(
  fs
    .readFileSync(path.join(process.cwd(), '.env.local'), 'utf8')
    .split(/\r?\n/)
    .filter((line) => line && !line.startsWith('#') && line.includes('='))
    .map((line) => {
      const idx = line.indexOf('=')
      return [line.slice(0, idx).trim(), line.slice(idx + 1).trim()]
    }),
)
const SECRET = secrets.POLYV_LIVE_APP_SECRET

// ---------------------------------------------------------------- 1. 接入状态
const status = await call('GET', '/api/smart-subtitles/status')
check(status.status === 200, '接入状态 HTTP 200', status.status)
check(status.json?.configured === true, '接入状态 configured=true', status.text.slice(0, 120))
const target = (status.json?.videos || []).find((v) => v.video === VIDEO)
check(!!target && target.vidConfigured === true, '目标视频已配置 VID', JSON.stringify(target))
check(target?.durationSec === 106, '时长基准 106s', String(target?.durationSec))

// ---------------------------------------------------------------- 2. 本地 SRT 未受影响
const local = await call('GET', `/api/subtitles?video=${encodeURIComponent(VIDEO)}`)
check(local.status === 200 && local.json?.cueCount === 12, '本地 SRT 仍是 12 条', JSON.stringify({
  status: local.status,
  cueCount: local.json?.cueCount,
}))
check(local.json?.subtitleFile === 'candidateflow-demo.srt', '本地 SRT 文件名不变', local.json?.subtitleFile)

// ---------------------------------------------------------------- 3. 登记既有任务（幂等）
const adopted = await call('POST', '/api/smart-subtitles/tasks/adopt', {
  video: VIDEO,
  taskId: TASK_ID,
  createdAt: '2026-10-23T15:24:43',
})
check(adopted.status === 201, '登记既有任务 HTTP 201', adopted.status + ' ' + adopted.text.slice(0, 160))
check(adopted.json?.taskId === TASK_ID, '登记返回真实 taskId', adopted.json?.taskId)
check(adopted.json?.status === 'ready', '登记后状态 ready', adopted.json?.status)
check(adopted.json?.subtitleCount === 29, '登记后字幕数 29', String(adopted.json?.subtitleCount))

const adoptedAgain = await call('POST', '/api/smart-subtitles/tasks/adopt', {
  video: VIDEO,
  taskId: TASK_ID,
})
const list = await call('GET', `/api/smart-subtitles/tasks?video=${encodeURIComponent(VIDEO)}`)
check(Array.isArray(list.json) && list.json.length === 1, '重复登记不产生第二条记录', String(list.json?.length))
check(adoptedAgain.status === 201, '重复登记幂等 201', adoptedAgain.status)

// ---------------------------------------------------------------- 4. 状态查询
const task = await call('GET', `/api/smart-subtitles/tasks/${TASK_ID}`)
check(task.status === 200 && task.json?.status === 'ready', '状态查询 ready', task.json?.status)
check(!!task.json?.lastCheckedAt && !!task.json?.lastSuccessAt, '记录了查询与成功时间', JSON.stringify({
  lastCheckedAt: task.json?.lastCheckedAt,
  lastSuccessAt: task.json?.lastSuccessAt,
}))
check(task.json?.errorCode === null, '无残留错误码', String(task.json?.errorCode))

// ---------------------------------------------------------------- 5. 字幕换算与阶段一记录一致
const cues = await call('GET', `/api/smart-subtitles/tasks/${TASK_ID}/cues`)
check(cues.status === 200 && cues.json?.totalCueCount === 29, '取回 29 条', String(cues.json?.totalCueCount))

const baseline = JSON.parse(fs.readFileSync(RESULT_FILE, 'utf8'))
const expected = baseline.cues || []
const actual = cues.json?.cues || []
check(actual.length === expected.length, '条数与阶段一审计一致', `${actual.length} vs ${expected.length}`)

let mismatch = []
for (let i = 0; i < Math.min(actual.length, expected.length); i++) {
  const a = actual[i]
  const e = expected[i]
  if (
    Math.abs(a.startSec - e.startSec) > 0.0011 ||
    Math.abs(a.endSec - e.endSec) > 0.0011 ||
    a.text !== e.text
  ) {
    mismatch.push({ i: i + 1, api: [a.startSec, a.endSec, a.text], audit: [e.startSec, e.endSec, e.text] })
  }
}
check(mismatch.length === 0, '毫秒转秒与文字逐条一致', JSON.stringify(mismatch.slice(0, 3)))

const ascending = actual.every((c, i) => i === 0 || c.startSec >= actual[i - 1].startSec)
check(ascending, '时间顺序升序', '')
const inRange = actual.every((c) => c.startSec >= 0 && c.endSec <= 107)
check(inRange, '未超出视频时长 + 容差', '')
check(actual[0]?.startSec === 0.68 && actual[0]?.endSec === 1.24, '首条 0.680-1.240', JSON.stringify(actual[0]))
check(actual.at(-1)?.endSec === 100.9, '末条结束 100.900', String(actual.at(-1)?.endSec))

// ---------------------------------------------------------------- 6. 时间窗按重叠筛选
const windowed = await call('GET', `/api/smart-subtitles/tasks/${TASK_ID}/cues?from=46.0&to=48.0`)
const wc = windowed.json?.cues || []
const allOverlap = wc.every((c) => c.endSec >= 46.0 && c.startSec <= 48.0)
check(windowed.json?.totalCueCount === 29, '窗口筛选仍报告总条数 29', String(windowed.json?.totalCueCount))
check(wc.length === 3 && allOverlap, '窗口 46-48s 返回 3 条且均与窗口重叠', JSON.stringify(wc.map((c) => [c.index, c.startSec, c.endSec])))
check(
  wc.some((c) => c.startSec < 46.0 && c.endSec > 46.0),
  '窗口边界被跨越的 cue 未被丢弃（重叠口径）',
  JSON.stringify(wc.map((c) => [c.startSec, c.endSec])),
)

// ---------------------------------------------------------------- 7. 防重复创建
const created = await call('POST', '/api/smart-subtitles/tasks', { video: VIDEO })
check(created.status === 200, '再次创建返回 200（复用）', String(created.status))
check(created.json?.reused === true, 'reused=true', String(created.json?.reused))
check(created.json?.taskId === TASK_ID, '复用的是既有 522143', String(created.json?.taskId))

// ---------------------------------------------------------------- 8. 凭据不泄露
const bodies = [status, local, adopted, task, cues, windowed, created].map((r) => r.text).join('\n')
check(!SECRET || !bodies.includes(SECRET), '响应体不含 appSecret', '')
check(!/sign=[0-9A-F]{32}/i.test(bodies), '响应体不含 sign 明文', '')

// ---------------------------------------------------------------- 输出
const failed = steps.filter((s) => !s.ok)
for (const s of steps) {
  console.log(`${s.ok ? 'PASS' : 'FAIL'}  ${s.label}${s.ok ? '' : '  -> ' + s.detail}`)
}
const report = {
  generatedAt: new Date().toISOString(),
  base: BASE,
  video: VIDEO,
  taskId: TASK_ID,
  checks: steps.length,
  failed: failed.length,
  problems,
  steps,
}
const outFile = path.join(process.cwd(), '_verify', 'smart-subtitle-backend-verify.json')
fs.writeFileSync(outFile, JSON.stringify(report, null, 2), 'utf8')
console.log(`\n共 ${steps.length} 项，失败 ${failed.length} 项；结果写入 ${outFile}`)
if (failed.length > 0) {
  process.exit(1)
}
