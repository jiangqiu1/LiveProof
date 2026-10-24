// 交接文档配图：真实浏览器截图（只读，不做任何数据写入）
//
// 与 verify-frontend.mjs 的区别：本脚本不 PATCH 用例、不创建待办，
// 只导航 + 点击字幕（纯前端交互）+ 截图，并可顺带收集控制台错误/异常。
//
// 用法：node scripts/capture-workbench-shots.mjs [baseUrl]
// 输出：docs/assets/frontend/*.png + docs/assets/frontend/capture-report.json

import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const BASE = process.argv[2] || 'http://127.0.0.1:5173'
/** 只截某一张：node scripts/capture-workbench-shots.mjs <base> 05 */
const ONLY = process.argv[3] || null
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9359
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
    timeout: 120000,
  })
  if (exceptionDetails) throw new Error(exceptionDetails.exception?.description || exceptionDetails.text)
  return result.value
}

async function shot(client, name) {
  const { data } = await client.send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(join(OUT, name), Buffer.from(data, 'base64'))
}

const PRELUDE = `
window.__cap = {
  sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
  async until(fn, timeout = 15000, label = '') {
    const end = Date.now() + timeout;
    while (Date.now() < end) { const v = fn(); if (v) return v; await new Promise((r) => setTimeout(r, 120)); }
    throw new Error('等待超时: ' + label);
  },
  cues: () => Array.from(document.querySelectorAll('.lp-cue')),
  items: () => Array.from(document.querySelectorAll('.lp-case-item')),
  overflow() {
    const de = document.documentElement;
    const bad = [];
    document.querySelectorAll('.lp-card, .lp-page, .lp-workbench, .lp-nav-item').forEach((el) => {
      if (el.scrollWidth > el.clientWidth + 2 && getComputedStyle(el).overflowX !== 'auto') {
        bad.push(el.className + ' ' + el.scrollWidth + '>' + el.clientWidth);
      }
    });
    return { scrollWidth: de.scrollWidth, innerWidth: window.innerWidth, bad };
  },
};
true;
`

async function main() {
  mkdirSync(OUT, { recursive: true })
  await fetch(BASE).catch(() => {
    throw new Error(`前端未启动：${BASE}`)
  })

  const userDataDir = mkdtempSync(join(tmpdir(), 'lp-cap-'))
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

  const report = { capturedAt: new Date().toISOString(), pages: [], consoleErrors: [] }
  let client
  try {
    await waitForDevtools()
    const targets = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()
    client = cdp(targets.find((t) => t.type === 'page').webSocketDebuggerUrl)
    await client.ready
    await client.send('Page.enable')
    await client.send('Runtime.enable')
    await client.send('Log.enable')
    await client.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })

    // ---- 工作台默认态 ----
    if (!ONLY || ['01', '02'].includes(ONLY)) {
      await client.send('Page.navigate', { url: BASE + '/workbench' })
      await sleep(2600)
      await evaluate(client, PRELUDE)

      if (!ONLY || ONLY === '01') {
        const workbench = await evaluate(
          client,
          `(async () => {
        await window.__cap.until(() => window.__cap.items().length > 0 && window.__cap.cues().length > 0, 20000, '工作台数据');
        const v = await window.__cap.until(() => {
          const el = document.querySelector('video');
          return el && el.duration > 0 ? el : null;
        }, 20000, '视频元数据');
        await window.__cap.sleep(600);
        return {
          caseCount: window.__cap.items().length,
          cueCount: window.__cap.cues().length,
          duration: Number(v.duration.toFixed(2)),
          activeSegs: Array.from(document.querySelectorAll('.lp-seg.is-active')).map((b) => b.textContent.trim()),
          question: document.querySelector('.lp-review-question')?.textContent.trim() ?? null,
          ...window.__cap.overflow(),
        };
      })()`,
        )
        report.pages.push({ page: '工作台（默认）', file: '01-workbench.png', ...workbench })
        await shot(client, '01-workbench.png')
      }

      // ---- 点击字幕后 ----
      if (!ONLY || ONLY === '02') {
        const afterCue = await evaluate(
          client,
          `(async () => {
        const cues = window.__cap.cues();
        const target = cues[2] || cues[0];
        const label = target.querySelector('.lp-cue-time').textContent.trim();
        target.click();
        await window.__cap.sleep(900);
        const v = document.querySelector('video');
        return {
          clickedAt: label,
          videoTime: Number(v.currentTime.toFixed(2)),
          highlighted: document.querySelectorAll('.lp-cue.is-current').length,
        };
      })()`,
        )
        report.pages.push({ page: '工作台（点击字幕定位后）', file: '02-workbench-seek.png', ...afterCue })
        await shot(client, '02-workbench-seek.png')
      }
    }

    // ---- 其它页面 ----
    for (const p of [
      { path: '/records', file: '03-records.png', label: '核验记录' },
      { path: '/todos', file: '04-todos.png', label: '待办复盘' },
      { path: '/materials', file: '05-materials.png', label: '素材与字幕来源' },
    ]) {
      if (ONLY && !p.file.startsWith(ONLY)) continue
      await client.send('Page.navigate', { url: BASE + p.path })
      await sleep(3000)
      await evaluate(client, PRELUDE)
      const info = await evaluate(
        client,
        `(async () => {
          await window.__cap.sleep(1200);
          // 有 <video> 的页面等首帧解码完成再截，避免截到加载占位圈（超时不阻塞截图）
          const v = document.querySelector('video');
          if (v) {
            const end = Date.now() + 8000;
            while (Date.now() < end && v.readyState < 2) await window.__cap.sleep(200);
            await window.__cap.sleep(400);
          }
          return {
            title: document.querySelector('.lp-page-title')?.textContent.trim() ?? null,
            videoReadyState: v ? v.readyState : null,
            ...window.__cap.overflow(),
          };
        })()`,
      )
      report.pages.push({ page: p.label, file: p.file, capturedAt: new Date().toISOString(), ...info })
      await shot(client, p.file)
    }

    // ---- 控制台错误汇总 ----
    for (const e of client.events) {
      if (e.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(e.params.type)) {
        report.consoleErrors.push({
          type: e.params.type,
          text: (e.params.args || []).map((a) => String(a.value ?? a.description ?? '')).join(' ').slice(0, 200),
        })
      }
      if (e.method === 'Runtime.exceptionThrown') {
        report.consoleErrors.push({ type: 'exception', text: String(e.params.exceptionDetails?.text || '').slice(0, 200) })
      }
      if (e.method === 'Log.entryAdded' && ['error', 'warning'].includes(e.params.entry.level)) {
        report.consoleErrors.push({ type: 'log:' + e.params.entry.level, text: String(e.params.entry.text || '').slice(0, 200) })
      }
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

  const target = join(OUT, 'capture-report.json')
  let final = report
  if (ONLY && existsSync(target)) {
    // 单页重截：保留其它页面原有记录，只替换本次这一页
    const prev = JSON.parse(readFileSync(target, 'utf8'))
    const merged = new Map((prev.pages || []).map((p) => [p.file, p]))
    for (const p of report.pages) merged.set(p.file, p)
    final = {
      capturedAt: prev.capturedAt,
      pages: [...merged.values()].sort((a, b) => String(a.file).localeCompare(String(b.file))),
      consoleErrors: report.consoleErrors,
      lastPartialCapture: { at: new Date().toISOString(), only: ONLY, consoleErrors: report.consoleErrors },
    }
  }
  writeFileSync(target, JSON.stringify(final, null, 2), 'utf8')
  console.log(JSON.stringify(final, null, 2))
}

main().catch((e) => {
  console.error('截图脚本失败：', e.message)
  process.exit(1)
})
