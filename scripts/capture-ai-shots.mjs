// AI 功能真实浏览器截图（只读：不 PATCH 用例、不创建待办）
//
// 用法：
//   node scripts/capture-ai-shots.mjs [baseUrl] [tag]
//   tag 默认 live，用于区分不同后端配置下截的图（例如 offline / stub）
//
// 输出：docs/assets/frontend/ai-<tag>-review.png
//       docs/assets/frontend/ai-<tag>-suggestion.png（仅当 AI 已配置且分析成功）
//       docs/assets/frontend/ai-<tag>-report.png
//       docs/assets/frontend/capture-ai-report.json

import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const BASE = process.argv[2] || 'http://127.0.0.1:5173'
const TAG = process.argv[3] || 'live'
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9361
const OUT = join(import.meta.dirname, '..', 'docs', 'assets', 'frontend')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

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
    timeout: 180000,
  })
  if (exceptionDetails) throw new Error(exceptionDetails.exception?.description || exceptionDetails.text)
  return result.value
}

async function shot(client, name) {
  const { data } = await client.send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(join(OUT, name), Buffer.from(data, 'base64'))
  return name
}

const PRELUDE = `
window.__ai = {
  sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
  async until(fn, timeout = 20000, label = '') {
    const end = Date.now() + timeout;
    while (Date.now() < end) { const v = fn(); if (v) return v; await new Promise((r) => setTimeout(r, 150)); }
    throw new Error('等待超时: ' + label);
  },
  panel: () => document.querySelector('.lp-ai'),
  button() {
    const p = document.querySelector('.lp-ai');
    if (!p) return null;
    return Array.from(p.querySelectorAll('button')).find((b) => /AI/.test(b.textContent || ''));
  },
  scrollTo(el) { el?.scrollIntoView({ block: 'start', behavior: 'instant' }); },
};
true;
`

async function main() {
  mkdirSync(OUT, { recursive: true })
  await fetch(BASE).catch(() => {
    throw new Error(`前端未启动：${BASE}`)
  })

  const userDataDir = mkdtempSync(join(tmpdir(), 'lp-ai-cap-'))
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

  const report = { capturedAt: new Date().toISOString(), tag: TAG, pages: [], consoleErrors: [] }
  let client
  try {
    await waitForDevtools()
    const targets = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()
    client = cdp(targets.find((t) => t.type === 'page').webSocketDebuggerUrl)
    await client.ready
    await client.send('Page.enable')
    await client.send('Runtime.enable')
    await client.send('Log.enable')
    await client.send('Emulation.setDeviceMetricsOverride', {
      width: 1440,
      height: 900,
      deviceScaleFactor: 1,
      mobile: false,
    })

    // ---- 工作台：AI 面板 ----
    await client.send('Page.navigate', { url: BASE + '/?case=cf-import-3-records' })
    await sleep(3000)
    await evaluate(client, PRELUDE)

    const initial = await evaluate(
      client,
      `(async () => {
        await window.__ai.until(() => window.__ai.panel(), 20000, 'AI 面板');
        await window.__ai.sleep(800);
        const panel = window.__ai.panel();
        window.__ai.scrollTo(panel);
        await window.__ai.sleep(300);
        const button = window.__ai.button();
        return {
          pill: panel.querySelector('.lp-pill')?.textContent.trim() ?? null,
          buttonLabel: button?.textContent.trim() ?? null,
          buttonDisabled: button ? button.disabled : null,
          hasResult: Boolean(panel.querySelector('.lp-ai-result')),
          note: panel.querySelector('.lp-note')?.textContent.trim().slice(0, 160) ?? null,
        };
      })()`,
    )
    const reviewFile = await shot(client, `ai-${TAG}-review.png`)
    report.pages.push({ page: '工作台 · AI 辅助核验入口', file: reviewFile, ...initial })

    // ---- 点击分析（仅当可用）----
    if (initial.buttonDisabled === false) {
      const clicked = await evaluate(
        client,
        `(async () => {
          const button = window.__ai.button();
          button.click();
          try {
            await window.__ai.until(() => {
              const p = window.__ai.panel();
              return p && (p.querySelector('.lp-ai-result') || p.querySelector('.lp-note.warn'));
            }, 120000, 'AI 结果');
          } catch (e) {
            return { error: String(e.message) };
          }
          await window.__ai.sleep(700);
          const panel = window.__ai.panel();
          const result = panel.querySelector('.lp-ai-result');
          window.__ai.scrollTo(panel);
          await window.__ai.sleep(400);
          return {
            hasResult: Boolean(result),
            summary: result?.querySelector('.lp-ai-summary')?.textContent.trim().slice(0, 120) ?? null,
            evidenceCount: result?.querySelectorAll('.lp-ai-evidence').length ?? 0,
            frameCount: result?.querySelectorAll('.lp-ai-frame img').length ?? 0,
            timeChips: Array.from(result?.querySelectorAll('.lp-ai-evidence-head .lp-chip') ?? []).map((b) =>
              b.textContent.trim(),
            ),
            warnings: Array.from(panel.querySelectorAll('.lp-note.warn, .lp-ai-notes')).map((n) =>
              n.textContent.trim().slice(0, 120),
            ),
            consoleErrorsHint: null,
          };
        })()`,
      )
      const suggestionFile = await shot(client, `ai-${TAG}-suggestion.png`)
      report.pages.push({ page: '工作台 · AI 建议', file: suggestionFile, ...clicked })

      // 再截一张滚到底部的：采纳按钮与"不改变核验进度"的说明
      const adoptInfo = await evaluate(
        client,
        `(async () => {
          const actions = document.querySelector('.lp-ai-actions');
          window.__ai.scrollTo(actions);
          await window.__ai.sleep(400);
          return {
            adoptLabel: document.querySelector('.lp-ai-actions .lp-btn-primary')?.textContent.trim() ?? null,
            foot: document.querySelector('.lp-ai-foot')?.textContent.trim() ?? null,
          };
        })()`,
      )
      const adoptFile = await shot(client, `ai-${TAG}-adopt.png`)
      report.pages.push({ page: '工作台 · 采纳 AI 建议', file: adoptFile, ...adoptInfo })
    }

    // ---- 核验记录：AI 复盘报告 ----
    await client.send('Page.navigate', { url: BASE + '/records' })
    await sleep(2600)
    await evaluate(client, PRELUDE)
    const reportInfo = await evaluate(
      client,
      `(async () => {
        await window.__ai.until(
          () => Array.from(document.querySelectorAll('button')).some((b) => /生成 AI 复盘报告/.test(b.textContent || '')),
          20000,
          '报告按钮',
        );
        const button = Array.from(document.querySelectorAll('button')).find((b) =>
          /生成 AI 复盘报告/.test(b.textContent || ''),
        );
        button.click();
        try {
          await window.__ai.until(() => document.querySelector('.lp-report-pre'), 120000, '报告预览');
        } catch (e) {
          return { error: String(e.message) };
        }
        await window.__ai.sleep(700);
        const pre = document.querySelector('.lp-report-pre');
        return {
          meta: document.querySelector('.lp-report-meta')?.textContent.replace(/\\s+/g, ' ').trim() ?? null,
          notes: Array.from(document.querySelectorAll('.el-dialog .lp-note div')).map((n) =>
            n.textContent.trim().slice(0, 120),
          ),
          markdownChars: pre?.textContent.length ?? 0,
          hasDownload: Array.from(document.querySelectorAll('.el-dialog button')).some((b) =>
            /下载 Markdown/.test(b.textContent || ''),
          ),
        };
      })()`,
    )
    const reportFile = await shot(client, `ai-${TAG}-report.png`)
    report.pages.push({ page: '核验记录 · AI 复盘报告', file: reportFile, ...reportInfo })

    // ---- 控制台错误 ----
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
      if (e.method === 'Log.entryAdded' && ['error', 'warning'].includes(e.params.entry.level)) {
        report.consoleErrors.push({
          type: 'log:' + e.params.entry.level,
          text: String(e.params.entry.text || '').slice(0, 200),
        })
      }
    }
  } finally {
    client?.close()
    chrome.kill()
    await sleep(400)
    try {
      rmSync(userDataDir, { recursive: true, force: true })
    } catch {
      /* Chrome 可能仍持有句柄 */
    }
  }

  const target = join(OUT, 'capture-ai-report.json')
  let merged = report
  if (existsSync(target)) {
    const prev = JSON.parse(readFileSync(target, 'utf8'))
    const pages = new Map((prev.pages || []).map((p) => [p.file, p]))
    for (const p of report.pages) pages.set(p.file, p)
    merged = {
      updatedAt: new Date().toISOString(),
      pages: [...pages.values()].sort((a, b) => String(a.file).localeCompare(String(b.file))),
      consoleErrors: report.consoleErrors,
    }
  }
  writeFileSync(target, JSON.stringify(merged, null, 2), 'utf8')
  console.log(JSON.stringify(merged, null, 2))
}

main().catch((e) => {
  console.error('AI 截图脚本失败：', e.message)
  process.exit(1)
})
