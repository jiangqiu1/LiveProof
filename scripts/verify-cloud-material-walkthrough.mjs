// P2 云端素材完整人工走查（真实浏览器，真实保利威链路）
//
// 走查链路：素材页选中云端素材 → 播放器出画面 → 工作台新建核验事项 → AI 核验取到云端帧
//
// 与 capture-*.mjs 的区别：这是「功能走查」不是配图，断言的是每一步是否真的通了，
// 而不是截得好看。所有断言都基于页面上的真实 DOM 与真实接口返回。
//
// 用法：node scripts/verify-cloud-material-walkthrough.mjs [baseUrl] [materialId]
// 输出：docs/assets/p2-check/walkthrough-report.json + 5 张关键步骤截图

import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const BASE = process.argv[2] || 'http://127.0.0.1:5173'
const API = process.env.LIVEPROOF_API || 'http://127.0.0.1:8080'
const MATERIAL_ID = process.argv[3] || null
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9361
const OUT = join(import.meta.dirname, '..', 'docs', 'assets', 'p2-check')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const steps = []
const problems = []

function record(name, ok, detail) {
  steps.push({ step: name, ok, detail })
  if (!ok) problems.push(name + '：' + JSON.stringify(detail).slice(0, 300))
  console.log((ok ? 'PASS ' : 'FAIL ') + name + ' ' + JSON.stringify(detail).slice(0, 400))
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
    }
  })
  return {
    ready,
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
}

async function api(path, init) {
  const res = await fetch(API + path, init)
  const text = await res.text()
  let body = null
  try {
    body = text ? JSON.parse(text) : null
  } catch {
    body = { raw: text.slice(0, 300) }
  }
  return { status: res.status, body }
}

const PRELUDE = `
window.__wt = {
  sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
  async until(fn, timeout = 20000, label = '') {
    const end = Date.now() + timeout;
    while (Date.now() < end) {
      let v; try { v = fn(); } catch (e) { v = null; }
      if (v) return v;
      await new Promise((r) => setTimeout(r, 150));
    }
    throw new Error('等待超时: ' + label);
  },
  text: (sel) => document.querySelector(sel)?.textContent.trim() ?? null,
  all: (sel) => Array.from(document.querySelectorAll(sel)),
};
true;
`

async function main() {
  mkdirSync(OUT, { recursive: true })
  await fetch(BASE).catch(() => {
    throw new Error(`前端未启动：${BASE}`)
  })

  // 先确认后端素材列表里有可用的云端素材
  const list = await api('/api/materials')
  const cloud = (list.body || []).filter((m) => m.storage === 'polyv' && m.status === 'ready')
  const target = MATERIAL_ID ? cloud.find((m) => m.id === MATERIAL_ID) : cloud[0]
  if (!target) throw new Error('没有就绪的云端素材，请先上传并等待转码')
  console.log('走查目标素材：', target.id, target.originalName)
  record('0. 云端素材就绪', true, {
    id: target.id,
    status: target.status,
    polyvStatus: target.polyvStatus,
    width: target.width,
    height: target.height,
    durationSec: target.durationSec,
    srtFileName: target.srtFileName,
  })

  const userDataDir = mkdtempSync(join(tmpdir(), 'lp-wt-'))
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

  const report = { capturedAt: new Date().toISOString(), materialId: target.id, steps: [], consoleErrors: [] }
  let client
  try {
    await waitForDevtools()
    const targets = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()
    client = cdp(targets.find((t) => t.type === 'page').webSocketDebuggerUrl)
    await client.ready
    await client.send('Page.enable')
    await client.send('Runtime.enable')
    await client.send('Log.enable')
    await client.send('Network.enable')
    await client.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })
    client.send('Runtime.addBinding', { name: '__logError' }).catch(() => {})

    // ---- 1. 素材页：选中云端素材，确认播放器真的出画面 ----
    await client.send('Page.navigate', { url: BASE + '/materials' })
    await sleep(3000)
    await evaluate(client, PRELUDE)

    const selected = await evaluate(
      client,
      `(async () => {
        const items = await window.__wt.until(() => {
          const xs = window.__wt.all('.lp-material-item');
          return xs.length ? xs : null;
        }, 20000, '素材列表');
        // 内置素材与上传素材可能同名（都用 candidateflow-demo-nosub.mp4），
        // 必须按右上角的「保利威 / 内置」pill 区分，否则会点到本地那份，
        // 播放地址就成了 /media/*.mp4，等于没走云端链路。
        const cloudItems = items.filter((el) => {
          const pills = Array.from(el.querySelectorAll('.lp-pill')).map((p) => p.textContent.trim());
          return pills.includes('保利威');
        });
        if (!cloudItems.length) return { error: '素材列表里没有「保利威」标记的条目', total: items.length };
        const target = cloudItems[0];
        const label = target.textContent.replace(/\\s+/g, ' ').trim().slice(0, 80);
        target.click();
        await window.__wt.sleep(1200);
        return {
          clicked: label,
          cloudItemCount: cloudItems.length,
          activeCount: window.__wt.all('.lp-material-item.is-active').length,
          activeLabel: document.querySelector('.lp-material-item.is-active')?.textContent.replace(/\\s+/g, ' ').trim().slice(0, 80) ?? null,
          hasVideo: !!document.querySelector('video'),
        };
      })()`,
    )
    record('1. 素材页可选中云端素材', selected.activeCount === 1 && selected.hasVideo, selected)
    await shot(client, 'wt-01-material-selected.png')

    // 播放器真正可播：readyState >= 2 表示有当前帧数据
    const playback = await evaluate(
      client,
      `(async () => {
        const v = await window.__wt.until(() => {
          const el = document.querySelector('video');
          return el && el.src && el.duration > 0 ? el : null;
        }, 30000, '视频就绪');
        const src = v.src;
        if (v.readyState < 2) {
          try { await v.play(); } catch (e) { /* 自动播放可能被拒 */ }
          const end = Date.now() + 15000;
          while (Date.now() < end && v.readyState < 2) { await window.__wt.sleep(200); }
        }
        return {
          srcPrefix: src.slice(0, 90),
          isPolyvCdn: /videocc\\.net|polyv/.test(src),
          duration: Number((v.duration || 0).toFixed(2)),
          readyState: v.readyState,
          videoWidth: v.videoWidth,
          videoHeight: v.videoHeight,
          currentTime: Number((v.currentTime || 0).toFixed(2)),
        };
      })()`,
    )
    record(
      '2. 云端素材在浏览器里真的能播（有帧数据 + 真实分辨率）',
      playback.readyState >= 2 && playback.videoWidth > 0 && playback.isPolyvCdn,
      playback,
    )
    await shot(client, 'wt-02-material-playing.png')

    // ---- 2. 工作台：带 ?material= 直接切到目标云端素材，新建核验事项 ----
    // 工作台支持 ?material=<id> 预设（WorkbenchView 的 presetMaterial），
    // 比在下拉框里模拟点选稳，也避免新事项误落到同名的内置素材下。
    await client.send('Page.navigate', { url: BASE + '/?material=' + encodeURIComponent(target.id) })
    await sleep(3500)
    await evaluate(client, PRELUDE)

    const added = await evaluate(
      client,
      `(async () => {
        await window.__wt.until(() => document.querySelector('.lp-page-title'), 20000, '工作台加载');
        // 打开「新建事项」入口
        const trigger = window.__wt.all('button').find((b) => /新建|新增事项|添加事项/.test(b.textContent));
        if (!trigger) return { error: '未找到新建事项入口', buttons: window.__wt.all('button').map((b) => b.textContent.trim()).slice(0, 20) };
        trigger.click();
        await window.__wt.sleep(900);
        const dialog = await window.__wt.until(() => document.querySelector('.el-dialog, .lp-dialog'), 8000, '弹窗');
        // 弹窗里有 4 个输入框（问题、承诺、开始秒、结束秒），只填前两个。
        // 按 placeholder 定位比按下标稳，避免 Element Plus 重组 DOM 后错位。
        const byPlaceholder = (re) =>
          Array.from(dialog.querySelectorAll('input, textarea')).find((el) => re.test(el.placeholder || ''));
        const q = byPlaceholder(/候选人记录|待核验/);
        const c = byPlaceholder(/5000|承诺/);
        if (!q || !c) {
          return {
            error: '未定位到问题/承诺输入框',
            placeholders: Array.from(dialog.querySelectorAll('input, textarea')).map((el) => el.placeholder),
          };
        }
        const fill = (el, value) => {
          const setter = Object.getOwnPropertyDescriptor(el.constructor.prototype, 'value').set;
          setter.call(el, value);
          el.dispatchEvent(new Event('input', { bubbles: true }));
          el.dispatchEvent(new Event('change', { bubbles: true }));
        };
        fill(q, '走查：导入 CSV 后候选人列表是否会同步更新？');
        fill(c, '走查：导入完成后候选人列表立即同步更新，成功 3 条失败 0 条');
        await window.__wt.sleep(400);
        const submit = window.__wt.all('button').find((b) => /确认新增|保存|确定|创建|提交/.test(b.textContent) && !b.disabled);
        if (!submit) return {
          error: '未找到提交按钮',
          buttons: window.__wt.all('button').map((b) => b.textContent.trim()).filter(Boolean),
        };
        submit.click();
        await window.__wt.sleep(3000);
        // 弹窗默认「连续新增」不自动关闭（keepOpen 设计），先关掉它再读列表，
        // 否则遮罩层会挡住后续按钮点击（AI 辅助核验在弹窗下面）。
        const closer = Array.from(document.querySelectorAll('.el-dialog button, .lp-dialog button'))
          .find((b) => /关闭/.test(b.textContent));
        if (closer) closer.click();
        await window.__wt.sleep(900);
        return {
          dialogGone: !document.querySelector('.el-dialog__wrapper:not([style*="display: none"])'),
          itemCount: window.__wt.all('.lp-case-item').length,
          hasWalkthroughItem: document.body.textContent.includes('走查：导入 CSV'),
        };
      })()`,
    )
    record('3. 工作台可新建核验事项', !added.error && added.itemCount > 0 && added.hasWalkthroughItem, added)
    await shot(client, 'wt-03-case-created.png')

    // 通过接口确认：走查新建的那条事项必须落在目标云端素材下
    const cases = await api('/api/cases')
    const mine = (cases.body || []).filter(
      (c) => c.materialId === target.id && (c.question || '').startsWith('走查：'),
    )
    record('4. 新事项归属到云端素材', mine.length === 1, {
      materialId: target.id,
      caseCount: mine.length,
      latest: mine[0] ? { id: mine[0].id, question: mine[0].question, evidenceType: mine[0].evidenceType } : null,
    })

    if (!mine.length) {
      throw new Error('事项未归属到目标素材，无法继续 AI 核验走查')
    }
    const caseId = mine[0].id

    // ---- 3. AI 核验：点击按钮，等真实模型返回 ----
    const review = await evaluate(
      client,
      `(async () => {
        // AI 核验按钮只对「已选中的事项」出现，先确保走查那条被选中
        const items = window.__wt.all('.lp-case-item');
        const walk = items.find((el) => el.textContent.includes('走查：导入 CSV'));
        if (!walk) return { error: '列表里没有走查事项', itemCount: items.length };
        if (!walk.classList.contains('is-active')) {
          walk.click();
          await window.__wt.sleep(1200);
        }
        const btn = window.__wt.all('button').find((b) => /AI\\s*辅助核验|AI\\s*核验|智能核验/.test(b.textContent));
        if (!btn) return {
          error: '未找到 AI 辅助核验按钮',
          selectedActive: window.__wt.all('.lp-case-item.is-active').length,
          buttons: window.__wt.all('button').map((b) => b.textContent.trim()).filter(Boolean),
        };
        btn.click();
        // 真实模型 + 保利威截图共需 30-40s，给足 110s
        const done = await window.__wt.until(() => {
          const t = document.body.textContent;
          return /已生成建议|采纳并应用|建议摘要|分析理由/.test(t) ? t : null;
        }, 110000, 'AI 建议返回');
        return {
          triggered: true,
          hasSummary: /建议摘要|已生成/.test(done),
          textSample: done.replace(/\\s+/g, ' ').slice(0, 220),
        };
      })()`,
    ).catch((e) => ({ error: e.message }))
    record('5. 前端点击 AI 核验并拿到建议', !review.error, review)
    await shot(client, 'wt-04-ai-suggestion.png')

    // ---- 4. 交叉核对：接口返回的帧尺寸必须是等比正确的 720x405 ----
    const aiResp = await api(`/api/cases/${caseId}/ai-review`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ subtitleSource: 'local' }),
    })
    const frames = aiResp.body?.context?.frames || []
    const allCorrect = frames.length > 0 && frames.every((f) => f.width === 720 && f.height === 405)
    record('6. 云端帧尺寸等比正确（720x405，非 720x1080）', allCorrect, {
      httpStatus: aiResp.status,
      visionUsed: aiResp.body?.context?.visionUsed,
      frameNote: aiResp.body?.context?.frameNote,
      frames: frames.map((f) => ({ sec: f.sec, w: f.width, h: f.height, bytes: f.bytes })),
    })
    await shot(client, 'wt-05-ai-done.png')

    report.steps = steps
    report.problems = problems
    report.caseId = caseId
    report.aiSuggestion = aiResp.body?.suggestion
      ? {
          answerStatus: aiResp.body.suggestion.answerStatus,
          evidenceType: aiResp.body.suggestion.evidenceType,
          evidenceSufficiency: aiResp.body.suggestion.evidenceSufficiency,
          summary: aiResp.body.suggestion.summary,
        }
      : null
    report.frames = frames.map((f) => ({ sec: f.sec, width: f.width, height: f.height, bytes: f.bytes }))
  } finally {
    try {
      client?.close()
    } catch {
      /* ignore */
    }
    chrome.kill()
  }

  writeFileSync(join(OUT, 'walkthrough-report.json'), JSON.stringify(report, null, 2))
  console.log('\n==== 走查结论 ====')
  console.log('通过 ' + (steps.length - problems.length) + '/' + steps.length)
  if (problems.length) {
    console.log('问题：')
    problems.forEach((p) => console.log(' - ' + p))
    process.exitCode = 1
  }
}

main().catch((e) => {
  console.error('走查失败：', e.message)
  process.exitCode = 1
})