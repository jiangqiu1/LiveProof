// 字幕来源切换端到端验证（真实 Chrome + CDP，无第三方依赖）
//
// 覆盖：工作台本地 SRT <-> 保利威智能字幕切换、云端 cue 条数与后端一致、
//       「仅当前字幕」窗口过滤、素材页云端任务面板、控制台无报错。
//
// 用法：node scripts/verify-subtitle-source.mjs [baseUrl]
// 前置：后端 8080（含已就绪的智能字幕任务）与前端 5173 均已启动。

import { spawn } from 'node:child_process'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const BASE = process.argv[2] || 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9361
const SHOT_DIR = join(import.meta.dirname, '..', 'docs', 'assets', 'frontend')

const CASE_ID = 'cf-import-3-records'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function waitForDevtools(timeoutMs = 25000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
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

async function firstPageTarget() {
  const res = await fetch(`http://127.0.0.1:${PORT}/json/list`)
  const targets = await res.json()
  const page = targets.find((t) => t.type === 'page')
  if (!page) throw new Error('没有 page target')
  return page
}

function cdp(wsUrl) {
  const ws = new WebSocket(wsUrl)
  let nextId = 1
  const pending = new Map()
  const listeners = []
  const ready = new Promise((resolve, reject) => {
    ws.addEventListener('open', () => resolve())
    ws.addEventListener('error', (e) => reject(new Error('WS 失败: ' + e.message)))
  })
  ws.addEventListener('message', (event) => {
    const msg = JSON.parse(event.data)
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id)
      pending.delete(msg.id)
      msg.error ? reject(new Error(msg.error.message)) : resolve(msg.result)
      return
    }
    listeners.forEach((fn) => fn(msg))
  })
  return {
    ready,
    on: (fn) => listeners.push(fn),
    send(method, params = {}) {
      const id = nextId++
      return new Promise((resolve, reject) => {
        pending.set(id, { resolve, reject })
        ws.send(JSON.stringify({ id, method, params }))
      })
    },
    close() {
      ws.close()
    },
  }
}

const PRELUDE = `
window.__src = {
  sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
  async until(fn, timeout = 15000, label = '') {
    const end = Date.now() + timeout;
    while (Date.now() < end) {
      const v = fn();
      if (v) return v;
      await new Promise((r) => setTimeout(r, 120));
    }
    throw new Error('等待超时: ' + label);
  },
  cues: () => Array.from(document.querySelectorAll('.lp-cue')),
  segs: () => Array.from(document.querySelectorAll('.lp-seg')),
  activeSegs: () => Array.from(document.querySelectorAll('.lp-seg.is-active')).map((b) => b.textContent.trim()),
  clickSeg(label) {
    const btn = this.segs().find((b) => b.textContent.trim() === label);
    if (!btn) throw new Error('找不到分段按钮: ' + label + '，实际: ' + this.segs().map((b) => b.textContent.trim()).join('/'));
    btn.click();
    return true;
  },
  pills: () => Array.from(document.querySelectorAll('.lp-pill')).map((el) => el.textContent.trim()),
  text: () => document.body.innerText,
};
true;
`

async function evaluate(client, expression, awaitPromise = true) {
  const { result, exceptionDetails } = await client.send('Runtime.evaluate', {
    expression,
    awaitPromise,
    returnByValue: true,
    timeout: 120000,
  })
  if (exceptionDetails) {
    throw new Error('页面脚本异常：' + (exceptionDetails.exception?.description || exceptionDetails.text))
  }
  return result.value
}

async function shot(client, name) {
  const { data } = await client.send('Page.captureScreenshot', { format: 'png' })
  mkdirSync(SHOT_DIR, { recursive: true })
  writeFileSync(join(SHOT_DIR, name), Buffer.from(data, 'base64'))
}

async function apiGet(path) {
  const res = await fetch(API + path)
  if (!res.ok) throw new Error(`GET ${path} → ${res.status}`)
  return res.json()
}

async function main() {
  await fetch(BASE).catch(() => {
    throw new Error(`前端未启动：${BASE}`)
  })

  const detail = await apiGet(`/api/cases/${CASE_ID}`)
  const local = await apiGet(`/api/subtitles?video=${encodeURIComponent(detail.video)}`)
  const tasks = await apiGet(`/api/smart-subtitles/tasks?video=${encodeURIComponent(detail.video)}`)
  const ready = tasks.find((t) => t.status === 'ready' && t.taskId)
  if (!ready) throw new Error('该视频没有就绪的智能字幕任务，请先在素材页创建或登记')
  const cloud = await apiGet(`/api/smart-subtitles/tasks/${ready.taskId}/cues`)
  const cloudWindow = await apiGet(
    `/api/smart-subtitles/tasks/${ready.taskId}/cues?from=${detail.startSec}&to=${detail.endSec}`,
  )

  const report = {
    caseId: CASE_ID,
    source: {
      local: { cueCount: local.cueCount, file: local.subtitleFile },
      cloud: { taskId: ready.taskId, cueCount: cloud.cueCount, windowCueCount: cloudWindow.cueCount },
    },
    steps: [],
    problems: [],
  }

  const userDataDir = mkdtempSync(join(tmpdir(), 'lp-src-'))
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
  const consoleErrors = []
  try {
    await waitForDevtools()
    const target = await firstPageTarget()
    client = cdp(target.webSocketDebuggerUrl)
    client.on((msg) => {
      if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
        consoleErrors.push(msg.params.args.map((a) => a.value ?? a.description ?? '').join(' '))
      }
      if (msg.method === 'Runtime.exceptionThrown') {
        consoleErrors.push('exception: ' + (msg.params.exceptionDetails?.text ?? 'unknown'))
      }
    })
    await client.ready
    await client.send('Page.enable')
    await client.send('Runtime.enable')
    await client.send('Emulation.setDeviceMetricsOverride', {
      width: 1440,
      height: 900,
      deviceScaleFactor: 1,
      mobile: false,
    })

    await client.send('Page.navigate', { url: `${BASE}/?case=${CASE_ID}` })
    await sleep(1500)
    await evaluate(client, PRELUDE, false)

    // ---- 1. 默认本地字幕 ----
    const initial = await evaluate(
      client,
      `(async () => {
        await window.__src.until(() => window.__src.cues().length > 0, 15000, '首屏字幕');
        return { count: window.__src.cues().length, active: window.__src.activeSegs(), pills: window.__src.pills() };
      })()`,
    )
    report.steps.push({ step: '默认来源为本地 SRT', ...initial })
    if (initial.count !== local.cueCount) {
      report.problems.push(`本地字幕条数不符：页面 ${initial.count}，后端 ${local.cueCount}`)
    }
    if (!initial.active.includes('本地 SRT')) {
      report.problems.push('默认未选中「本地 SRT」：' + initial.active.join('/'))
    }

    // ---- 2. 切到智能字幕 ----
    const cloudView = await evaluate(
      client,
      `(async () => {
        window.__src.clickSeg('智能字幕');
        await window.__src.until(
          () => window.__src.cues().length === ${cloud.cueCount} || document.querySelector('.lp-note.warn'),
          20000,
          '云端字幕渲染',
        );
        return {
          count: window.__src.cues().length,
          active: window.__src.activeSegs(),
          pills: window.__src.pills(),
          warn: document.querySelector('.lp-note.warn')?.textContent?.trim() ?? null,
          firstCue: window.__src.cues()[0]?.innerText?.replace(/\\n/g, ' ').trim() ?? null,
        };
      })()`,
    )
    report.steps.push({ step: '切换到智能字幕', ...cloudView })
    if (cloudView.count !== cloud.cueCount) {
      report.problems.push(`云端字幕条数不符：页面 ${cloudView.count}，后端 ${cloud.cueCount}`)
    }
    if (!cloudView.active.includes('智能字幕')) {
      report.problems.push('切换后未选中「智能字幕」：' + cloudView.active.join('/'))
    }
    if (!cloudView.pills.includes('智能字幕')) {
      report.problems.push('卡片标题未标注「智能字幕」：' + cloudView.pills.join('/'))
    }
    if (cloudView.warn) {
      report.problems.push('切换云端来源出现错误提示：' + cloudView.warn)
    }
    await shot(client, 'subtitle-source-cloud.png')

    // ---- 3. 仅当前字幕（窗口过滤） ----
    const windowed = await evaluate(
      client,
      `(async () => {
        const sw = document.querySelector('.lp-card-head .el-switch');
        if (!sw) throw new Error('找不到「仅当前字幕」开关');
        sw.click();
        await window.__src.until(() => window.__src.cues().length <= ${cloudWindow.cueCount}, 8000, '窗口过滤');
        await window.__src.sleep(300);
        return { count: window.__src.cues().length };
      })()`,
    )
    report.steps.push({ step: '仅当前字幕（云端窗口）', ...windowed })
    // 前端按 cue 与窗口重叠过滤，边界口径（严格/非严格）可能与后端差 1 条
    if (Math.abs(windowed.count - cloudWindow.cueCount) > 1) {
      report.problems.push(
        `窗口过滤条数差异过大：页面 ${windowed.count}，后端 ${cloudWindow.cueCount}`,
      )
    }

    // ---- 4. 切回本地 SRT ----
    const backLocal = await evaluate(
      client,
      `(async () => {
        const sw = document.querySelector('.lp-card-head .el-switch');
        if (sw && sw.classList.contains('is-checked')) sw.click();
        window.__src.clickSeg('本地 SRT');
        await window.__src.until(() => window.__src.cues().length === ${local.cueCount}, 15000, '切回本地字幕');
        return { count: window.__src.cues().length, active: window.__src.activeSegs() };
      })()`,
    )
    report.steps.push({ step: '切回本地 SRT', ...backLocal })
    if (backLocal.count !== local.cueCount) {
      report.problems.push(`切回本地后条数不符：页面 ${backLocal.count}，后端 ${local.cueCount}`)
    }

    // ---- 5. 素材页云端任务面板 ----
    await client.send('Page.navigate', { url: `${BASE}/materials` })
    await sleep(1800)
    await evaluate(client, PRELUDE, false)
    const media = await evaluate(
      client,
      `(async () => {
        const text = await window.__src.until(
          () => (document.body.innerText.includes('保利威智能字幕') ? document.body.innerText : null),
          15000,
          '素材页云端面板',
        );
        const buttons = Array.from(document.querySelectorAll('button')).map((b) => ({
          label: b.textContent.trim(),
          disabled: b.disabled,
        }));
        return {
          hasTaskId: text.includes('${ready.taskId}'),
          hasReady: text.includes('已就绪'),
          buttons: buttons.filter((b) => ['复查云端状态', '复用 / 创建任务', '创建智能字幕任务'].includes(b.label)),
        };
      })()`,
    )
    report.steps.push({ step: '素材页云端任务面板', ...media })
    if (!media.hasTaskId) report.problems.push(`素材页未显示任务 ID ${ready.taskId}`)
    if (!media.hasReady) report.problems.push('素材页未显示「已就绪」状态')
    if (!media.buttons.some((b) => b.label === '复查云端状态' && !b.disabled)) {
      report.problems.push('素材页「复查云端状态」按钮不可用')
    }
    await shot(client, 'media-smart-subtitle.png')

    report.consoleErrors = consoleErrors
    if (consoleErrors.length > 0) {
      report.problems.push('控制台报错：' + consoleErrors.slice(0, 3).join(' | '))
    }
  } finally {
    client?.close()
    chrome.kill()
    await sleep(500)
    try {
      rmSync(userDataDir, { recursive: true, force: true })
    } catch {
      /* Chrome 可能仍持有句柄 */
    }
  }

  writeFileSync(
    join(import.meta.dirname, '..', '_verify', 'subtitle-source-report.json'),
    JSON.stringify(report, null, 2),
  )
  console.log(JSON.stringify(report, null, 2))
  if (report.problems.length > 0) {
    console.error('\n字幕来源验证未通过：')
    for (const p of report.problems) console.error(' - ' + p)
    process.exitCode = 1
  } else {
    console.log('\n字幕来源验证通过：本地 SRT / 智能字幕切换、窗口过滤、素材页任务面板')
  }
}

main().catch((e) => {
  console.error('字幕来源验证脚本失败：', e.message)
  process.exitCode = 1
})
