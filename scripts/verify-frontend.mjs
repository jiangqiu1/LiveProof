// 前端工作台端到端验证（真实 Chrome + CDP，无第三方依赖）
//
// 覆盖：真实用例渲染 / 视频可播可拖 / 字幕点击定位 / 四字段改判保存 /
//       刷新后状态恢复 / 待办创建与完成 / 桌面无横向溢出。
//
// 用法：node scripts/verify-frontend.mjs [baseUrl]
// 前置：后端 8080 与前端 5173 均已启动。

import { spawn } from 'node:child_process'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const BASE = process.argv[2] || 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9333
const SHOT_DIR = join(import.meta.dirname, '..', 'frontend', 'verify-shots')

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
    }
  })
  return {
    ready,
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

// 页面内通用工具
const PRELUDE = `
window.__lp = {
  sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
  async until(fn, timeout = 12000, label = '') {
    const end = Date.now() + timeout;
    while (Date.now() < end) {
      const v = fn();
      if (v) return v;
      await new Promise((r) => setTimeout(r, 100));
    }
    throw new Error('等待超时: ' + label);
  },
  segButtons: () => Array.from(document.querySelectorAll('.lp-seg')),
  clickSeg(label) {
    const btn = this.segButtons().find((b) => b.textContent.trim() === label);
    if (!btn) throw new Error('找不到分段按钮: ' + label);
    btn.click();
    return true;
  },
  activeSegs() {
    return this.segButtons().filter((b) => b.classList.contains('is-active')).map((b) => b.textContent.trim());
  },
  caseItems: () => Array.from(document.querySelectorAll('.lp-case-item')),
  cues: () => Array.from(document.querySelectorAll('.lp-cue')),
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
  writeFileSync(join(SHOT_DIR, name), Buffer.from(data, 'base64'))
}

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

async function main() {
  mkdirSync(SHOT_DIR, { recursive: true })
  await fetch(BASE).catch(() => {
    throw new Error(`前端未启动：${BASE}`)
  })

  const cases = await apiGet('/api/cases?ds=cf')
  if (cases.length === 0) throw new Error('数据库没有 cf 用例，无法验证')
  const firstCase = cases[0]
  const secondCase = cases[1] ?? null
  const original = await apiGet(`/api/cases/${firstCase.id}`)

  const userDataDir = mkdtempSync(join(tmpdir(), 'lp-ui-'))
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

  const report = { steps: [], problems: [] }
  let client
  try {
    await waitForDevtools()
    const target = await firstPageTarget()
    client = cdp(target.webSocketDebuggerUrl)
    await client.ready
    await client.send('Page.enable')
    await client.send('Runtime.enable')
    await client.send('Emulation.setDeviceMetricsOverride', {
      width: 1440,
      height: 900,
      deviceScaleFactor: 1,
      mobile: false,
    })

    // 工作台已从 / 迁到 /workbench，根路径现在是产品首页
    await client.send('Page.navigate', { url: BASE + '/workbench' })
    await sleep(1500)
    await evaluate(client, PRELUDE, false)

    // ---- 1. 列表来自数据库 ----
    const listInfo = await evaluate(
      client,
      `(async () => {
        await window.__lp.until(() => window.__lp.caseItems().length > 0, 15000, '用例列表');
        return {
          count: window.__lp.caseItems().length,
          firstQuestion: window.__lp.caseItems()[0].querySelector('.lp-case-q').textContent.trim(),
          firstMeta: window.__lp.caseItems()[0].querySelector('.lp-case-meta').textContent.trim(),
        };
      })()`,
    )
    report.steps.push({ step: '用例列表渲染', ...listInfo })
    if (listInfo.count !== cases.length) {
      report.problems.push(`列表条数 ${listInfo.count} 与数据库 ${cases.length} 不一致`)
    }
    if (!listInfo.firstQuestion.includes((firstCase.question ?? '').slice(0, 10))) {
      report.problems.push('首条问题文案与数据库不一致')
    }

    // ---- 2. 视频可播可拖 ----
    const videoInfo = await evaluate(
      client,
      `(async () => {
        const v = await window.__lp.until(() => {
          const el = document.querySelector('video');
          return el && el.readyState >= 1 && el.duration > 0 ? el : null;
        }, 20000, '视频元数据');
        await v.play().catch(() => {});
        const seek = async (t) => {
          await new Promise((resolve, reject) => {
            const done = () => { v.removeEventListener('seeked', done); resolve(); };
            v.addEventListener('seeked', done);
            v.currentTime = t;
            setTimeout(() => { v.removeEventListener('seeked', done); reject(new Error('seek 超时 ' + t)); }, 10000);
          });
          return Number(v.currentTime.toFixed(2));
        };
        const a = await seek(30);
        const b = await seek(80);
        const c = await seek(12.5);
        v.pause();
        return { duration: Number(v.duration.toFixed(2)), seek30: a, seek80: b, seek12_5: c, src: v.currentSrc };
      })()`,
    )
    report.steps.push({ step: '视频播放与拖动', ...videoInfo })
    if (!(videoInfo.duration > 100)) report.problems.push('视频时长异常：' + videoInfo.duration)
    for (const key of ['seek30', 'seek80', 'seek12_5']) {
      const target = Number(key.replace('seek', '').replace('_5', '.5'))
      if (Math.abs(videoInfo[key] - target) > 0.8) {
        report.problems.push(`${key} 拖动未命中：${videoInfo[key]}`)
      }
    }

    // ---- 3. 字幕来自真实 SRT，点击定位 ----
    const cueInfo = await evaluate(
      client,
      `(async () => {
        const cues = await window.__lp.until(() => window.__lp.cues().length > 0 ? window.__lp.cues() : null, 12000, '字幕列表');
        const target = cues[2];
        const label = target.querySelector('.lp-cue-time').textContent.trim();
        const text = target.querySelector('.lp-cue-text').textContent.trim();
        target.click();
        await window.__lp.sleep(700);
        const v = document.querySelector('video');
        return {
          cueCount: cues.length,
          clickedAt: label,
          clickedText: text.slice(0, 24),
          videoTimeAfterClick: Number(v.currentTime.toFixed(2)),
          highlighted: document.querySelectorAll('.lp-cue.is-current').length,
        };
      })()`,
    )
    report.steps.push({ step: '字幕点击定位', ...cueInfo })
    if (cueInfo.cueCount < 5) report.problems.push('字幕条数过少：' + cueInfo.cueCount)
    const [mm, ss] = cueInfo.clickedAt.split(':').map(Number)
    const cueStart = mm * 60 + ss
    if (Math.abs(cueInfo.videoTimeAfterClick - cueStart) > 1.2) {
      report.problems.push(`点击字幕未定位到 ${cueStart}s，实际 ${cueInfo.videoTimeAfterClick}s`)
    }

    await shot(client, '01-workbench.png')

    // ---- 3.5 右栏必须被限制在一屏内，操作按钮可滚动到 ----
    const layoutInfo = await evaluate(
      client,
      `(() => {
        const cards = document.querySelectorAll('.lp-workbench > *');
        const card = cards[cards.length - 1];
        const r = card.getBoundingClientRect();
        const review = card.querySelector('.lp-review');
        const foot = card.querySelector('.lp-foot-actions');
        document.querySelector('.lp-review').scrollTop = 9999;
        const footBox = foot ? foot.getBoundingClientRect() : null;
        return {
          cardBottom: Math.round(r.bottom),
          innerHeight: window.innerHeight,
          docScrollHeight: document.documentElement.scrollHeight,
          panelScrollable: review.scrollHeight > review.clientHeight,
          footVisibleAfterScroll: footBox ? footBox.bottom <= window.innerHeight + 1 && footBox.top > 0 : false,
        };
      })()`,
    )
    report.steps.push({ step: '右栏滚动与按钮可达', ...layoutInfo })
    if (layoutInfo.cardBottom > layoutInfo.innerHeight) {
      report.problems.push(`右栏卡片超出视口：bottom=${layoutInfo.cardBottom} > ${layoutInfo.innerHeight}`)
    }
    // AI 解释收进问号浮层、删除冗余按钮后，右栏通常已完整放进一屏（scrollHeight == clientHeight）。
    // 只有内容真的超出视口时才要求内部可滚动。
    if (layoutInfo.cardBottom > layoutInfo.innerHeight && !layoutInfo.panelScrollable) {
      report.problems.push(`右栏超出视口（bottom=${layoutInfo.cardBottom}）且内部不可滚动`)
    }
    if (!layoutInfo.footVisibleAfterScroll) report.problems.push('滚动后仍看不到保存按钮')
    await sleep(200)
    await shot(client, '06-review-bottom.png')

    // ---- 4. 切换用例，右栏跟随 ----
    if (secondCase) {
      const switchInfo = await evaluate(
        client,
        `(async () => {
          const before = document.querySelector('.lp-review-question').textContent.trim();
          window.__lp.caseItems()[1].click();
          await window.__lp.until(() => {
            const q = document.querySelector('.lp-review-question');
            return q && q.textContent.trim() !== before ? q.textContent.trim() : null;
          }, 12000, '切换用例');
          const segs = window.__lp.activeSegs();
          const videoTime = Number(document.querySelector('video').currentTime.toFixed(2));
          return { question: document.querySelector('.lp-review-question').textContent.trim(), segs, videoTime };
        })()`,
      )
      report.steps.push({ step: '切换用例', ...switchInfo })
      if (!switchInfo.question.includes((secondCase.question ?? '').slice(0, 10))) {
        report.problems.push('切换后右栏问题未跟随')
      }
    }

    // 切回第一条，准备改判
    await evaluate(
      client,
      `(async () => {
        window.__lp.caseItems()[0].click();
        await window.__lp.until(() => {
          const q = document.querySelector('.lp-review-question');
          return q && q.textContent.includes(${JSON.stringify((firstCase.question ?? '').slice(0, 10))});
        }, 12000, '切回第一条');
        return true;
      })()`,
    )

    // 先把首条重置到与目标值不同的确定状态。
    // 脚本末尾会把首条改回这里的目标值，若不先重置，第二次运行点击等于没改，
    // hasChanges 恒为 false，「未保存提示」断言会假失败（幂等性要求）。
    await evaluate(
      client,
      `(async () => {
        for (const label of ['尚未检查', '未找到回答', '待确认', '尚未评估']) window.__lp.clickSeg(label);
        await window.__lp.sleep(250);
        const save = Array.from(document.querySelectorAll('.lp-btn')).find((b) => /保存核验结果/.test(b.textContent));
        if (!save) throw new Error('未找到保存按钮');
        save.click();
        await window.__lp.until(() => !document.querySelector('.lp-dirty'), 12000, '基线重置落库');
        await window.__lp.sleep(400);
        return window.__lp.activeSegs();
      })()`,
    )

    // ---- 5. 四字段分别保存 ----
    const TARGETS = {
      reviewStatus: { ui: '已核验', api: 'reviewed' },
      answerStatus: { ui: '部分回答', api: 'partially_answered' },
      evidenceType: { ui: '口头承诺', api: 'verbal_claim' },
      evidenceSufficiency: { ui: '证据不足', api: 'insufficient' },
    }
    const changed = await evaluate(
      client,
      `(async () => {
        const targets = ${JSON.stringify(Object.values(TARGETS).map((t) => t.ui))};
        for (const label of targets) window.__lp.clickSeg(label);
        await window.__lp.sleep(300);
        const dirty = document.querySelector('.lp-dirty');
        return { active: window.__lp.activeSegs(), dirty: dirty ? dirty.textContent.trim() : null };
      })()`,
    )
    report.steps.push({ step: '四字段改判（未保存）', ...changed })
    if (!changed.dirty) report.problems.push('改判后未出现未保存提示')

    await evaluate(
      client,
      `(async () => {
        // 顶部「保存核验结果」按钮已删除（第五轮瘦身），保存入口只剩右栏底部
        const btn = Array.from(document.querySelectorAll('.lp-btn')).find((b) => /保存核验结果/.test(b.textContent));
        if (!btn) throw new Error('未找到右栏底部保存按钮');
        btn.click();
        await window.__lp.until(() => !document.querySelector('.lp-dirty'), 15000, '保存完成');
        return true;
      })()`,
    )
    const afterSave = await apiGet(`/api/cases/${firstCase.id}`)
    report.steps.push({
      step: '保存后后端字段',
      reviewStatus: afterSave.reviewStatus,
      answerStatus: afterSave.answerStatus,
      evidenceType: afterSave.evidenceType,
      evidenceSufficiency: afterSave.evidenceSufficiency,
    })
    for (const [field, target] of Object.entries(TARGETS)) {
      if (afterSave[field] !== target.api) {
        report.problems.push(`${field} 未保存：期望 ${target.api}，实际 ${afterSave[field]}`)
      }
    }

    const listTag = await evaluate(
      client,
      `(async () => {
        await window.__lp.sleep(300);
        return window.__lp.caseItems()[0].querySelector('.lp-pill').textContent.trim();
      })()`,
    )
    report.steps.push({ step: '左栏状态同步', tag: listTag })
    if (listTag !== '已核验') report.problems.push('保存后左栏状态未同步：' + listTag)

    // ---- 6. 刷新后恢复 ----
    await client.send('Page.reload', { ignoreCache: true })
    await sleep(2500)
    await evaluate(client, PRELUDE, false)
    const afterReload = await evaluate(
      client,
      `(async () => {
        await window.__lp.until(() => window.__lp.caseItems().length > 0, 15000, '刷新后列表');
        await window.__lp.until(() => document.querySelector('.lp-review-question'), 15000, '刷新后详情');
        await window.__lp.sleep(600);
        return {
          question: document.querySelector('.lp-review-question').textContent.trim(),
          active: window.__lp.activeSegs(),
          listTag: window.__lp.caseItems()[0].querySelector('.lp-pill').textContent.trim(),
        };
      })()`,
    )
    report.steps.push({ step: '刷新后恢复', ...afterReload })
    const expectActive = ['已核验', '部分回答', '口头承诺', '证据不足']
    for (const label of expectActive) {
      if (!afterReload.active.includes(label)) {
        report.problems.push('刷新后未恢复：' + label + '（实际 ' + afterReload.active.join('/') + '）')
      }
    }
    if (afterReload.listTag !== '已核验') report.problems.push('刷新后左栏状态未恢复')

    // ---- 7. 待办：创建 → 完成 → 持久化 ----
    await evaluate(client, `(async () => { const nav = Array.from(document.querySelectorAll('.lp-nav-item')).find((b) => b.textContent.includes('待办复盘')); if (!nav) throw new Error('侧栏没有「待办复盘」'); nav.click(); await window.__lp.sleep(1200); return true; })()`)
    const todoCreate = await evaluate(
      client,
      `(async () => {
        await window.__lp.until(() => document.querySelector('.lp-page-title') && document.querySelector('.lp-page-title').textContent.includes('待办复盘'), 8000, '待办页');
        const createBtn = Array.from(document.querySelectorAll('.lp-btn')).find((b) => b.textContent.trim() === '新建待办');
        createBtn.click();
        const dialog = await window.__lp.until(() => document.querySelector('.el-dialog'), 6000, '新建待办弹窗');
        const title = 'E2E 校验待办 ' + new Date().toTimeString().slice(0, 8);
        const input = dialog.querySelector('.el-input__inner');
        input.value = title;
        input.dispatchEvent(new Event('input', { bubbles: true }));
        await window.__lp.sleep(200);
        const ok = Array.from(dialog.querySelectorAll('.lp-btn')).find((b) => b.textContent.trim() === '保存');
        ok.click();
        await window.__lp.until(() => !document.querySelector('.el-dialog__wrapper:not([style*="display: none"])'), 10000, '弹窗关闭');
        await window.__lp.sleep(800);
        return { title, rows: document.querySelectorAll('.el-table__body tr').length };
      })()`,
    )
    report.steps.push({ step: '待办创建', ...todoCreate })
    const todosAfterCreate = await apiGet('/api/todos')
    const created = todosAfterCreate.find((t) => t.title === todoCreate.title)
    if (!created) report.problems.push('待办未写入后端')
    else if (created.status !== 'open') report.problems.push('新建待办状态不是 open')

    if (created) {
      const completeInfo = await evaluate(
        client,
        `(async () => {
          const rows = Array.from(document.querySelectorAll('.el-table__body tr'));
          const row = rows.find((r) => r.textContent.includes(${JSON.stringify(todoCreate.title)}));
          if (!row) throw new Error('列表找不到刚创建的待办');
          const btn = Array.from(row.querySelectorAll('.lp-btn')).find((b) => b.textContent.trim() === '完成');
          btn.click();
          await window.__lp.sleep(1500);
          const updated = Array.from(document.querySelectorAll('.el-table__body tr')).find((r) =>
            r.textContent.includes(${JSON.stringify(todoCreate.title)}),
          );
          return { rowText: updated ? updated.textContent.trim().slice(0, 80) : null };
        })()`,
      )
      report.steps.push({ step: '待办完成', ...completeInfo })
      const todosAfterDone = await apiGet('/api/todos')
      const done = todosAfterDone.find((t) => t.title === todoCreate.title)
      if (!done || done.status !== 'done' || !done.completedAt) {
        report.problems.push('待办完成状态未持久化')
      }
      if (completeInfo.rowText && !completeInfo.rowText.includes('已完成')) {
        report.problems.push('待办行状态未刷新为已完成')
      }
    }
    await shot(client, '02-todos.png')

    // ---- 8. 其它页面截图与溢出检查 ----
    const pages = [
      { path: '/materials', name: '03-media.png', title: '素材库' },
      { path: '/records', name: '04-records.png', title: '核验记录' },
      { path: '/workbench', name: '05-workbench.png', title: '视频证据核验' },
      { path: '/tasks', name: '06-tasks.png', title: '核验项目' },
    ]
    const overflowAll = []
    for (const page of pages) {
      await client.send('Page.navigate', { url: BASE + page.path })
      await sleep(2600)
      await evaluate(client, PRELUDE, false)
      const info = await evaluate(
        client,
        `(async () => {
          await window.__lp.sleep(1200);
          return {
            title: document.querySelector('.lp-page-title')?.textContent.trim() ?? null,
            ...window.__lp.overflow(),
          };
        })()`,
      )
      report.steps.push({ step: '页面检查 ' + page.title, ...info })
      if (info.scrollWidth > info.innerWidth + 2) {
        report.problems.push(`${page.title} 出现横向滚动：${info.scrollWidth} > ${info.innerWidth}`)
      }
      if (info.bad.length > 0) report.problems.push(`${page.title} 元素溢出：` + info.bad.join(' | '))
      await shot(client, page.name)
      overflowAll.push(info)
    }

    // ---- 收尾：把第一条用例恢复为验证前的取值 ----
    const restored = await apiPatch(`/api/cases/${firstCase.id}`, {
      reviewStatus: original.reviewStatus,
      answerStatus: original.answerStatus,
      evidenceType: original.evidenceType,
      evidenceSufficiency: original.evidenceSufficiency,
    })
    report.steps.push({
      step: '恢复原始改判状态',
      reviewStatus: restored.reviewStatus,
      answerStatus: restored.answerStatus,
      evidenceType: restored.evidenceType,
      evidenceSufficiency: restored.evidenceSufficiency,
    })
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

  console.log(JSON.stringify(report, null, 2))
  if (report.problems.length > 0) {
    console.error('\n前端验证未通过：')
    for (const p of report.problems) console.error(' - ' + p)
    process.exitCode = 1
  } else {
    console.log('\n前端验证通过：用例/视频/字幕/四字段/刷新恢复/待办/布局')
  }
}

main().catch((e) => {
  console.error('前端验证脚本失败：', e.message)
  process.exit(1)
})
