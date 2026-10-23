// 关联时间点持久化端到端验证（真实 Chrome + CDP，只碰 timePoints 一个字段）
//
// 覆盖：定位视频 → 标记当前进度 → 保存 → 刷新恢复 → 删除 → 保存 → 刷新确认删除，
//       并全程核对四个核验状态字段未被意外改动。
//
// 用法：node scripts/verify-timepoints.mjs [baseUrl]
// 前置：后端 8080 与前端 5173 均已启动。
//
// 数据安全：脚本只对「左栏第一条用例」的 timePoints 做读写，结束时无论成败都 PATCH 回原值，
// 并在 finally 里再校验一次；不动四个枚举字段，不创建待办。
// 输出：docs/assets/frontend/verify-timepoints.log（完整报告）

import { spawn } from 'node:child_process'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const BASE = process.argv[2] || 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9361
const OUT = join(import.meta.dirname, '..', 'docs', 'assets', 'frontend')
const TARGET_SEC = 40

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function apiGet(path) {
  const res = await fetch(API + path)
  if (!res.ok) throw new Error(`GET ${path} → ${res.status}`)
  return res.json()
}

async function apiPatch(path, body) {
  const res = await fetch(API + path, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) throw new Error(`PATCH ${path} → ${res.status}`)
  return res.json()
}

/** 轮询直到 predicate 成立，返回最后一次 GET 的详情 */
async function waitDetail(id, predicate, timeout = 8000) {
  const end = Date.now() + timeout
  let last = null
  while (Date.now() < end) {
    last = await apiGet(`/api/cases/${id}`)
    if (predicate(last)) return last
    await sleep(250)
  }
  return last
}

async function waitForDevtools(t = 25000) {
  const end = Date.now() + t
  while (Date.now() < end) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/json/version`)
      if (res.ok) return res.json()
    } catch {
      /* 未就绪 */
    }
    await sleep(300)
  }
  throw new Error('Chrome DevTools 未就绪')
}

function cdp(wsUrl) {
  const ws = new WebSocket(wsUrl)
  let id = 1
  const pending = new Map()
  const events = []
  const ready = new Promise((res, rej) => {
    ws.addEventListener('open', () => res())
    ws.addEventListener('error', (e) => rej(new Error(e.message)))
  })
  ws.addEventListener('message', (ev) => {
    const m = JSON.parse(ev.data)
    if (m.id && pending.has(m.id)) {
      const { resolve: res, reject } = pending.get(m.id)
      pending.delete(m.id)
      m.error ? reject(new Error(m.error.message)) : res(m.result)
    } else if (m.method) {
      events.push(m)
    }
  })
  return {
    ready,
    events,
    send: (method, params = {}) =>
      new Promise((res, rej) => {
        const n = id++
        pending.set(n, { resolve: res, reject: rej })
        ws.send(JSON.stringify({ id: n, method, params }))
      }),
    close: () => ws.close(),
  }
}

async function evaluate(client, expression) {
  const { result, exceptionDetails } = await client.send('Runtime.evaluate', {
    expression,
    awaitPromise: true,
    returnByValue: true,
    timeout: 120000,
  })
  if (exceptionDetails) throw new Error(exceptionDetails.exception?.description || exceptionDetails.text)
  return result.value
}

const PRELUDE = `
window.__tp = {
  sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
  async until(fn, timeout = 20000, label = '') {
    const end = Date.now() + timeout;
    while (Date.now() < end) { const v = fn(); if (v) return v; await new Promise((r) => setTimeout(r, 120)); }
    throw new Error('等待超时: ' + label);
  },
  btn(text) {
    return Array.from(document.querySelectorAll('button'))
      .filter((b) => b.textContent.replace(/\\s+/g, '').includes(text) && b.offsetParent !== null)
      .pop() || null;
  },
  chips() {
    return Array.from(document.querySelectorAll('.lp-chip')).map((c) => ({
      text: c.textContent.replace(/\\s+/g, ' ').trim(),
      pending: c.classList.contains('pending'),
    }));
  },
  chip(text) {
    return Array.from(document.querySelectorAll('.lp-chip')).find((c) => c.textContent.includes(text)) || null;
  },
  dirty() {
    const el = document.querySelector('.lp-dirty');
    return el ? el.textContent.replace(/\\s+/g, ' ').trim() : null;
  },
  saveDisabled() {
    const b = window.__tp.btn('保存核验结果') || window.__tp.btn('保存中');
    return b ? b.disabled : null;
  },
  segs() {
    return Array.from(document.querySelectorAll('.lp-seg.is-active')).map((b) => b.textContent.trim());
  },
};
true;
`

const report = { startedAt: new Date().toISOString(), targetSec: TARGET_SEC, steps: [], problems: [] }
const d = (v) => (v === null || v === undefined ? null : Number(Number(v).toFixed(2)))
const samePoints = (a, b) => a.length === b.length && a.every((v, i) => Math.abs(v - b[i]) < 0.02)

async function main() {
  mkdirSync(OUT, { recursive: true })
  await fetch(BASE).catch(() => {
    throw new Error(`前端未启动：${BASE}`)
  })

  // ---- 0. 选目标用例与基线（与界面一致：列表按 startSec 升序，默认选中第一条）----
  const cases = await apiGet('/api/cases?ds=cf')
  if (!cases.length) throw new Error('数据库里没有 cf 用例，无法验证')
  const target = cases[0]
  const baseline = await apiGet(`/api/cases/${target.id}`)
  report.caseId = target.id
  report.baseline = {
    reviewStatus: baseline.reviewStatus,
    answerStatus: baseline.answerStatus,
    evidenceType: baseline.evidenceType,
    evidenceSufficiency: baseline.evidenceSufficiency,
    timePoints: baseline.timePoints,
  }
  report.steps.push({ step: '选定用例与基线', caseId: target.id, question: target.question, ...report.baseline })

  const userDataDir = mkdtempSync(join(tmpdir(), 'lp-tp-'))
  const chrome = spawn(
    CHROME,
    [
      '--headless=new',
      `--remote-debugging-port=${PORT}`,
      `--user-data-dir=${userDataDir}`,
      '--no-first-run',
      '--no-default-browser-check',
      '--autoplay-policy=no-user-gesture-required',
      '--mute-audio',
      '--window-size=1440,900',
      'about:blank',
    ],
    { stdio: 'ignore' },
  )

  let client
  try {
    await waitForDevtools()
    const targets = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()
    client = cdp(targets.find((t) => t.type === 'page').webSocketDebuggerUrl)
    await client.ready
    await client.send('Page.enable')
    await client.send('Runtime.enable')
    await client.send('Emulation.setDeviceMetricsOverride', {
      width: 1440,
      height: 900,
      deviceScaleFactor: 1,
      mobile: false,
    })

    const boot = async () => {
      await sleep(2600)
      await evaluate(client, PRELUDE)
      return evaluate(
        client,
        `(async () => {
          await window.__tp.until(() => document.querySelectorAll('.lp-case-item').length > 0, 20000, '用例列表');
          const v = await window.__tp.until(() => {
            const el = document.querySelector('video');
            return el && el.duration > 0 ? el : null;
          }, 20000, '视频元数据');
          await window.__tp.sleep(800);
          return {
            question: document.querySelector('.lp-review-question')?.textContent.trim() ?? null,
            duration: Number(v.duration.toFixed(2)),
            chips: window.__tp.chips(),
            segs: window.__tp.segs(),
          };
        })()`,
      )
    }

    // ---- 1. 打开工作台 ----
    await client.send('Page.navigate', { url: BASE + '/' })
    const first = await boot()
    report.steps.push({ step: '1 打开工作台', ...first })
    if (first.question !== target.question) {
      report.problems.push(`默认选中的用例与预期不一致：${first.question} ≠ ${target.question}`)
    }

    // ---- 2. 定位到 00:40 并标记 ----
    const marked = await evaluate(
      client,
      `(async () => {
        const v = document.querySelector('video');
        v.currentTime = ${TARGET_SEC};
        await window.__tp.sleep(900);
        const before = window.__tp.chips().length;
        const btn = window.__tp.btn('标记当前进度');
        if (!btn) return { error: '找不到「标记当前进度」按钮', before };
        btn.click();
        await window.__tp.sleep(500);
        return {
          videoTime: Number(v.currentTime.toFixed(2)),
          chipsBefore: before,
          chips: window.__tp.chips(),
          dirty: window.__tp.dirty(),
          saveDisabled: window.__tp.saveDisabled(),
        };
      })()`,
    )
    report.steps.push({ step: '2 定位 40s 并点击「标记当前进度」', ...marked })
    if (marked.error) report.problems.push(marked.error)
    const newChip = (marked.chips || []).find((c) => c.text.includes('00:40'))
    if (!newChip) report.problems.push('新标记未出现在时间点区')
    else if (!newChip.pending) report.problems.push('新标记未显示为未保存（缺少 pending 样式）')
    if (!marked.dirty) report.problems.push('标记后未出现未保存提示')
    if (marked.saveDisabled !== false) report.problems.push('标记后保存按钮仍不可用')

    // ---- 3. 保存并核对后端 ----
    const saved = await evaluate(
      client,
      `(async () => {
        const btn = window.__tp.btn('保存核验结果');
        if (!btn) return { error: '找不到保存按钮' };
        btn.click();
        await window.__tp.sleep(1600);
        return {
          chips: window.__tp.chips(),
          dirty: window.__tp.dirty(),
          saveDisabled: window.__tp.saveDisabled(),
        };
      })()`,
    )
    const afterSave = await waitDetail(target.id, (x) => x.timePoints.length > 0)
    report.steps.push({
      step: '3 保存核验结果',
      ...saved,
      api: { timePoints: afterSave.timePoints },
    })
    if (saved.error) report.problems.push(saved.error)
    if (!afterSave.timePoints.length) report.problems.push('保存后后端 timePoints 仍为空')
    if (!samePoints(afterSave.timePoints, [TARGET_SEC])) {
      report.problems.push(`后端 timePoints 与预期 [${TARGET_SEC}] 不一致：${JSON.stringify(afterSave.timePoints)}`)
    }
    if (saved.dirty) report.problems.push('保存后仍有未保存提示')
    if ((saved.chips || []).some((c) => c.pending)) report.problems.push('保存后标记仍显示为未保存')

    // ---- 4. 刷新后恢复 ----
    await client.send('Page.reload')
    const afterReload = await boot()
    const reloadedChip = (afterReload.chips || []).find((c) => c.text.includes('00:40'))
    report.steps.push({ step: '4 刷新后检查', ...afterReload })
    if (!reloadedChip) report.problems.push('刷新后时间点丢失')
    else if (reloadedChip.pending) report.problems.push('刷新后标记仍显示为未保存状态')
    if (afterReload.segs.join('/') !== first.segs.join('/')) {
      report.problems.push(`刷新后四字段显示发生变化：${first.segs.join('/')} → ${afterReload.segs.join('/')}`)
    }

    // ---- 5. 删除标记并保存 ----
    const removed = await evaluate(
      client,
      `(async () => {
        const chip = window.__tp.chip('00:40');
        if (!chip) return { error: '刷新后找不到 00:40 标记' };
        const x = chip.querySelector('.x');
        if (!x) return { error: '标记上没有删除按钮' };
        x.click();
        await window.__tp.sleep(400);
        const afterRemove = window.__tp.chips();
        window.__tp.btn('保存核验结果')?.click();
        await window.__tp.sleep(1600);
        return { afterRemove, dirty: window.__tp.dirty(), saveDisabled: window.__tp.saveDisabled() };
      })()`,
    )
    const afterClear = await waitDetail(target.id, (x) => x.timePoints.length === 0)
    report.steps.push({ step: '5 删除标记并保存', ...removed, api: { timePoints: afterClear.timePoints } })
    if (removed.error) report.problems.push(removed.error)
    if ((removed.afterRemove || []).some((c) => c.text.includes('00:40'))) {
      report.problems.push('点击 × 后标记仍在界面上')
    }
    if (afterClear.timePoints.length !== 0) {
      report.problems.push(`删除后后端 timePoints 未清空：${JSON.stringify(afterClear.timePoints)}`)
    }

    // ---- 6. 再次刷新确认删除已持久化 ----
    await client.send('Page.reload')
    const finalState = await boot()
    report.steps.push({ step: '6 刷新确认已删除', ...finalState })
    if ((finalState.chips || []).some((c) => c.text.includes('00:40'))) {
      report.problems.push('刷新后已删除的时间点又出现')
    }

    // ---- 7. 四字段独立性核对 ----
    const afterAll = await apiGet(`/api/cases/${target.id}`)
    const fieldDiff = ['reviewStatus', 'answerStatus', 'evidenceType', 'evidenceSufficiency'].filter(
      (k) => afterAll[k] !== baseline[k],
    )
    report.steps.push({
      step: '7 四字段独立性',
      before: report.baseline,
      after: {
        reviewStatus: afterAll.reviewStatus,
        answerStatus: afterAll.answerStatus,
        evidenceType: afterAll.evidenceType,
        evidenceSufficiency: afterAll.evidenceSufficiency,
        timePoints: afterAll.timePoints,
      },
      changedFields: fieldDiff,
    })
    if (fieldDiff.length) report.problems.push('四字段被意外改动：' + fieldDiff.join(','))

    // ---- 8. 控制台错误收集 ----
    for (const e of client.events) {
      if (e.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(e.params.type)) {
        report.consoleErrors.push({
          type: e.params.type,
          text: (e.params.args || []).map((a) => String(a.value ?? a.description ?? '')).join(' ').slice(0, 200),
        })
      }
      if (e.method === 'Runtime.exceptionThrown') {
        report.consoleErrors.push({
          type: 'exception',
          text: String(e.params.exceptionDetails?.text || '').slice(0, 200),
        })
      }
    }
  } finally {
    // 无论成败都恢复 timePoints 原值
    try {
      const restored = await apiPatch(`/api/cases/${target.id}`, { timePoints: report.baseline.timePoints })
      report.restore = { timePoints: restored.timePoints, ok: samePoints(restored.timePoints, report.baseline.timePoints) }
      if (!report.restore.ok) report.problems.push('收尾恢复 timePoints 失败')
    } catch (e) {
      report.restore = { error: e.message }
      report.problems.push('收尾恢复 timePoints 异常：' + e.message)
    }
    client?.close()
    chrome.kill()
    await sleep(500)
    try {
      rmSync(userDataDir, { recursive: true, force: true })
    } catch {
      /* Chrome 可能仍持有句柄 */
    }
  }

  report.finishedAt = new Date().toISOString()
  report.consoleErrors = report.consoleErrors || []
  writeFileSync(join(OUT, 'verify-timepoints.log'), JSON.stringify(report, null, 2), 'utf8')
  console.log(JSON.stringify(report, null, 2))
  if (report.problems.length) {
    console.error('\n时间点验证未通过：')
    for (const p of report.problems) console.error(' - ' + p)
    process.exitCode = 1
  } else {
    console.log('\n时间点验证通过：标记 → 保存 → 刷新恢复 → 删除 → 保存 → 刷新确认')
  }
}

main().catch((e) => {
  console.error('时间点验证脚本失败：', e.message)
  process.exit(1)
})
