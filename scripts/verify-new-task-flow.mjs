// 新建核验任务向导（P4–P6）端到端验证（真实 Chrome + CDP，无第三方依赖）
//
// 覆盖：/tasks 列表入口 / 侧栏高亮 / 三步向导 / 素材选择与转码态提示 /
//       字幕方式三选一 / 录入方式三选一 / 手动新增落库 / 进工作台带素材参数 / 记录页素材范围。
//
// 用法：node scripts/verify-new-task-flow.mjs [baseUrl]
// 前置：后端 8080 与前端 5173 均已启动，且数据库已有 ready 素材与至少 1 条事项。
//
// 只读为主：仅在第 3 步「手动新增」时会真实写入 1 条事项（带 E2E 前缀），
// 并在结束时调用 DELETE 清理，不残留脏数据。

import { spawn } from 'node:child_process'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const BASE = process.argv[2] || 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9341
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

// 页面内工具：只依赖真实 DOM，不假设组件内部实现
const PRELUDE = `
window.__lpw = {
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
  text(el) { return (el?.textContent || '').replace(/\\s+/g, ' ').trim(); },
  steps() {
    return Array.from(document.querySelectorAll('.el-step')).map((el) => ({
      title: this.text(el.querySelector('.el-step__title')),
      active: !!el.querySelector('.is-process'),
      finish: !!el.querySelector('.is-success'),
    }));
  },
  activeNav() {
    const item = document.querySelector('.lp-nav-item.is-active');
    return this.text(item);
  },
  materialItems() {
    return Array.from(document.querySelectorAll('.lp-material-item')).map((el, i) => ({
      index: i,
      label: this.text(el),
      active: el.classList.contains('is-active'),
    }));
  },
  radioCards() {
    return Array.from(document.querySelectorAll('.lp-radio-card')).map((el, i) => ({
      index: i,
      label: this.text(el),
      active: el.classList.contains('is-active'),
    }));
  },
  clickRadioCard(keyword) {
    const card = Array.from(document.querySelectorAll('.lp-radio-card'))
      .find((el) => this.text(el).includes(keyword));
    if (!card) throw new Error('找不到选项卡片: ' + keyword);
    card.click();
    return this.text(card);
  },
  clickMaterial(index) {
    const items = document.querySelectorAll('.lp-material-item');
    if (!items[index]) throw new Error('找不到素材项: ' + index);
    items[index].click();
    return this.text(items[index]);
  },
  buttonByText(keyword) {
    return Array.from(document.querySelectorAll('button'))
      .find((b) => this.text(b) === keyword || this.text(b).includes(keyword)) || null;
  },
  clickButton(keyword) {
    const btn = this.buttonByText(keyword);
    if (!btn) throw new Error('找不到按钮: ' + keyword);
    if (btn.disabled) throw new Error('按钮被禁用: ' + keyword);
    btn.click();
    return true;
  },
  buttonDisabled(keyword) {
    const btn = this.buttonByText(keyword);
    return btn ? btn.disabled : null;
  },
  bodyText() { return this.text(document.body); },
  pagePath() { return location.pathname + location.search; },
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
    throw new Error(
      '页面脚本异常：' + (exceptionDetails.exception?.description || exceptionDetails.text),
    )
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

async function apiDelete(path) {
  const res = await fetch(API + path, { method: 'DELETE' })
  if (!res.ok && res.status !== 404) throw new Error(`DELETE ${path} → ${res.status}`)
}

async function main() {
  mkdirSync(SHOT_DIR, { recursive: true })
  await fetch(BASE).catch(() => {
    throw new Error(`前端未启动：${BASE}`)
  })

  // 前置数据：至少一个 ready 的云端或内置素材
  const materials = await apiGet('/api/materials')
  const readyMaterial = materials.find((m) => m.status === 'ready')
  if (!readyMaterial) throw new Error('没有 ready 素材，无法验证向导第 1 步')

  const userDataDir = mkdtempSync(join(tmpdir(), 'lp-wiz-'))
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
  let createdCaseId = null
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

    // ---- 1. /tasks 列表页与侧栏高亮 ----
    await client.send('Page.navigate', { url: BASE + '/tasks' })
    await sleep(1600)
    await evaluate(client, PRELUDE, false)

    const tasksPage = await evaluate(
      client,
      `(async () => {
        await window.__lpw.sleep(600);
        return {
          path: window.__lpw.pagePath(),
          nav: window.__lpw.activeNav(),
          hasNewButton: !!window.__lpw.buttonByText('新建核验任务'),
          text: window.__lpw.bodyText().slice(0, 200),
        };
      })()`,
    )
    report.steps.push({ step: '/tasks 列表页', ...tasksPage })
    if (!tasksPage.path.startsWith('/tasks')) report.problems.push('/tasks 路由未生效')
    if (!tasksPage.nav.includes('核验任务')) {
      report.problems.push(`侧栏高亮错误：期望「核验任务」，实际「${tasksPage.nav}」`)
    }
    if (!tasksPage.hasNewButton) report.problems.push('/tasks 缺少「新建核验任务」入口')
    await shot(client, 'new-task-1-tasks-list.png')

    // ---- 2. 进入向导，核对三步标题 ----
    await evaluate(client, `window.__lpw.clickButton('新建核验任务'); true`, false)
    await sleep(1500)
    await evaluate(client, PRELUDE, false)

    const wizardStep1 = await evaluate(
      client,
      `(async () => {
        await window.__lpw.until(() => document.querySelectorAll('.el-step').length >= 3, 10000, '步骤条');
        return {
          path: window.__lpw.pagePath(),
          nav: window.__lpw.activeNav(),
          steps: window.__lpw.steps(),
          materials: window.__lpw.materialItems(),
          activeMaterialCount: document.querySelectorAll('.lp-material-item.is-active').length,
          nextDisabled: window.__lpw.buttonDisabled('下一步'),
          hint: window.__lpw.bodyText(),
        };
      })()`,
    )
    report.steps.push({ step: '向导第 1 步 选择素材', ...wizardStep1 })
    if (wizardStep1.steps.length !== 3) {
      report.problems.push(`步骤条应有 3 步，实际 ${wizardStep1.steps.length}`)
    }
    const titles = wizardStep1.steps.map((s) => s.title)
    if (!titles[0]?.includes('选择素材')) report.problems.push('第 1 步标题不对：' + titles[0])
    if (!titles[1]?.includes('字幕')) report.problems.push('第 2 步标题不对：' + titles[1])
    if (!titles[2]?.includes('核验事项')) report.problems.push('第 3 步标题不对：' + titles[2])
    // new-task 是 tasks 的子页面，侧栏必须仍高亮「核验任务」
    if (!wizardStep1.nav.includes('核验任务')) {
      report.problems.push(`new-task 侧栏高亮错误：实际「${wizardStep1.nav}」`)
    }
    // 「下一步」的禁用逻辑：有就绪素材时前端会自动选中第一条，此时按钮应可用；
    // 一条就绪素材都没有时必须禁用，否则会带着空素材进第 2 步。
    const readyNow = materials.filter((m) => m.status === 'ready')
    if (readyNow.length > 0 && wizardStep1.nextDisabled === true) {
      report.problems.push('已有就绪素材且自动选中时，「下一步」不应禁用')
    }
    if (readyNow.length === 0 && wizardStep1.nextDisabled !== true) {
      report.problems.push('没有任何就绪素材时，「下一步」应禁用')
    }
    if (wizardStep1.activeMaterialCount !== undefined && readyNow.length > 0 && wizardStep1.activeMaterialCount !== 1) {
      report.problems.push(`应自动选中 1 条就绪素材，实际 ${wizardStep1.activeMaterialCount}`)
    }
    const readyCount = materials.filter((m) => m.status === 'ready').length
    if (wizardStep1.materials.length !== readyCount) {
      report.problems.push(
        `第 1 步素材数 ${wizardStep1.materials.length} 与 ready 素材数 ${readyCount} 不一致（只应列已就绪）`,
      )
    }
    await shot(client, 'new-task-2-step1.png')

    // ---- 3. 选中素材 → 下一步 ----
    const picked = await evaluate(
      client,
      `(async () => {
        const label = window.__lpw.clickMaterial(0);
        await window.__lpw.sleep(400);
        const disabled = window.__lpw.buttonDisabled('下一步');
        window.__lpw.clickButton('下一步');
        await window.__lpw.sleep(900);
        return {
          label,
          disabledAfterPick: disabled,
          steps: window.__lpw.steps(),
          cards: window.__lpw.radioCards(),
        };
      })()`,
    )
    report.steps.push({ step: '选中素材并进入第 2 步', ...picked })
    if (picked.disabledAfterPick !== false) {
      report.problems.push('选中素材后「下一步」仍禁用')
    }
    if (picked.steps[1]?.active !== true) report.problems.push('未进入第 2 步')
    if (picked.cards.length !== 3) {
      report.problems.push(`第 2 步应有 3 个字幕方式，实际 ${picked.cards.length}`)
    }
    await shot(client, 'new-task-3-step2.png')

    // ---- 4. 选「暂不准备字幕」→ 第 3 步 ----
    const step3 = await evaluate(
      client,
      `(async () => {
        window.__lpw.clickRadioCard('暂不');
        await window.__lpw.sleep(400);
        window.__lpw.clickButton('下一步');
        await window.__lpw.sleep(900);
        return { steps: window.__lpw.steps(), cards: window.__lpw.radioCards() };
      })()`,
    )
    report.steps.push({ step: '跳过字幕进入第 3 步', ...step3 })
    if (step3.steps[2]?.active !== true) report.problems.push('未进入第 3 步')
    if (step3.cards.length !== 3) {
      report.problems.push(`第 3 步应有 3 种录入方式，实际 ${step3.cards.length}`)
    }
    // 产品红线文案必须存在
    const step3Text = await evaluate(client, `window.__lpw.bodyText()`, false)
    if (!step3Text.includes('不是 AI 自动生成')) {
      report.problems.push('第 3 步缺少「不是 AI 自动生成」的红线说明')
    }
    await shot(client, 'new-task-4-step3.png')

    // ---- 5. 手动新增（真实写库，随后清理）----
    // 第 3 步的三张卡片本身就是可点击入口，点击即打开对应弹窗
    const marker = `E2E 向导校验 ${Date.now()}`
    await evaluate(
      client,
      `(async () => {
        window.__lpw.clickRadioCard('手动新增');
        await window.__lpw.sleep(900);
        return true;
      })()`,
    )
    const dialogOpen = await evaluate(
      client,
      `(async () => {
        await window.__lpw.until(() => document.querySelector('.el-dialog'), 8000, '新增弹窗');
        return window.__lpw.text(document.querySelector('.el-dialog'));
      })()`,
    )
    if (!dialogOpen.includes('核验事项') && !dialogOpen.includes('新增')) {
      report.problems.push('手动新增弹窗未出现')
    }

    // 填表：用原生 setter 触发 Vue 的 v-model
    const filled = await evaluate(
      client,
      `(async () => {
        const setVal = (el, v) => {
          const proto = el.tagName === 'TEXTAREA'
            ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
          Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v);
          el.dispatchEvent(new Event('input', { bubbles: true }));
          el.dispatchEvent(new Event('change', { bubbles: true }));
        };
        const dialog = document.querySelector('.el-dialog');
        const inputs = Array.from(dialog.querySelectorAll('input:not([type=file]), textarea'));
        if (inputs.length < 2) throw new Error('弹窗输入框不足: ' + inputs.length);
        setVal(inputs[0], ${JSON.stringify(marker)});
        setVal(inputs[1], 'E2E 校验用承诺，不参与演示');
        await window.__lpw.sleep(300);
        return { inputCount: inputs.length };
      })()`,
    )
    report.steps.push({ step: '填写手动新增表单', ...filled })

    await evaluate(
      client,
      `(async () => {
        const dialog = document.querySelector('.el-dialog');
        const btn = Array.from(dialog.querySelectorAll('button'))
          .find((b) => /保存|确定|新增/.test(window.__lpw.text(b)));
        if (!btn) throw new Error('弹窗内找不到提交按钮');
        btn.click();
        await window.__lpw.sleep(1800);
        return true;
      })()`,
    )

    // 从后端核对是否真的落库
    const afterCreate = await apiGet(`/api/cases?ds=cf&materialId=${readyMaterial.id}`)
    const created = afterCreate.find((c) => (c.question || '').includes(marker))
    if (created) {
      createdCaseId = created.id
      report.steps.push({
        step: '手动新增已落库',
        id: created.id,
        source: created.source,
        reviewStatus: created.reviewStatus,
        answerStatus: created.answerStatus,
        evidenceType: created.evidenceType,
        evidenceSufficiency: created.evidenceSufficiency,
      })
      // 四字段必须是服务端固定占位值
      if (created.source !== 'manual') report.problems.push('新增事项 source 应为 manual')
      if (created.reviewStatus !== 'unreviewed') report.problems.push('新增事项 reviewStatus 应为 unreviewed')
      if (created.answerStatus !== 'not_found') {
        report.problems.push('新增事项 answerStatus 应为占位值 not_found')
      }
      if (created.evidenceType !== 'unknown') report.problems.push('新增事项 evidenceType 应为 unknown')
      if (created.evidenceSufficiency !== 'not_checked') {
        report.problems.push('新增事项 evidenceSufficiency 应为 not_checked')
      }
    } else {
      report.problems.push('手动新增后未在数据库查到该事项')
    }
    await shot(client, 'new-task-5-manual-created.png')

    // ---- 6. 完成向导 → 工作台带素材参数 ----
    const finished = await evaluate(
      client,
      `(async () => {
        window.__lpw.clickButton('进入工作台');
        await window.__lpw.sleep(1600);
        return { path: window.__lpw.pagePath(), nav: window.__lpw.activeNav() };
      })()`,
    )
    report.steps.push({ step: '完成向导跳转工作台', ...finished })
    if (!finished.path.includes('material=')) {
      report.problems.push('完成后未带 material 参数跳转工作台：' + finished.path)
    }
    await shot(client, 'new-task-6-workbench.png')

    // ---- 7. 记录页素材范围下拉 ----
    await client.send('Page.navigate', { url: BASE + '/records' })
    await sleep(1800)
    await evaluate(client, PRELUDE, false)
    const records = await evaluate(
      client,
      `(async () => {
        await window.__lpw.until(() => document.querySelector('.el-select'), 12000, '素材范围下拉');
        const text = window.__lpw.bodyText();
        return {
          hasSelect: !!document.querySelector('.el-select'),
          mentionsScope: text.includes('范围') || text.includes('全部素材'),
          hasReportButton: !!window.__lpw.buttonByText('生成'),
        };
      })()`,
    )
    report.steps.push({ step: '记录页素材范围', ...records })
    if (!records.hasSelect) report.problems.push('记录页缺少素材范围下拉')
    if (!records.hasReportButton) report.problems.push('记录页缺少生成报告入口')
    await shot(client, 'new-task-7-records-scope.png')

    // ---- 8. 无横向溢出 ----
    const overflow = await evaluate(
      client,
      `(() => {
        const de = document.documentElement;
        const bad = [];
        document.querySelectorAll('.lp-card, .lp-page, .lp-wizard, .lp-wizard-grid').forEach((el) => {
          if (el.scrollWidth > el.clientWidth + 2 && getComputedStyle(el).overflowX !== 'auto') {
            bad.push(el.className + ' ' + el.scrollWidth + '>' + el.clientWidth);
          }
        });
        return { scrollWidth: de.scrollWidth, innerWidth: window.innerWidth, bad };
      })()`,
    )
    report.steps.push({ step: '布局溢出检查', ...overflow })
    if (overflow.scrollWidth > overflow.innerWidth + 2) {
      report.problems.push(`页面横向溢出 ${overflow.scrollWidth} > ${overflow.innerWidth}`)
    }
    if (overflow.bad.length) report.problems.push('局部溢出：' + overflow.bad.join('; '))
  } finally {
    if (createdCaseId) {
      try {
        await apiDelete(`/api/cases/${createdCaseId}`)
        report.cleanup = `已删除临时事项 ${createdCaseId}`
      } catch (e) {
        report.cleanup = `清理失败，需手动删除 ${createdCaseId}：${e.message}`
        report.problems.push(report.cleanup)
      }
    }
    client?.close()
    chrome.kill()
  }

  const ok = report.problems.length === 0
  console.log(JSON.stringify(report, null, 2))
  console.log(ok ? '\nRESULT: PASS' : `\nRESULT: FAIL (${report.problems.length} 个问题)`)
  process.exit(ok ? 0 : 1)
}

main().catch((e) => {
  console.error('验证脚本异常：', e)
  process.exit(1)
})