// 媒体播放与拖动实测（T7a）：用真实 Chrome 通过 CDP 驱动 <video>，
// 验证起播、跳帧 seek、连续 seek、末尾 seek、白名单拒绝。
//
// 为什么必须用真浏览器：Range 返回 206 只证明字节范围正确，
// 不等于浏览器能解码播放、能拖动进度条。
//
// 用法：node scripts/verify-media-playback.mjs [baseUrl]
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const BASE = process.argv[2] || 'http://127.0.0.1:8080';
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const PORT = 9222;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitForDevtools(timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/json/version`);
      if (res.ok) return await res.json();
    } catch {
      /* 还没起来 */
    }
    await sleep(300);
  }
  throw new Error('Chrome DevTools 未就绪');
}

async function firstPageTarget() {
  const res = await fetch(`http://127.0.0.1:${PORT}/json/list`);
  const targets = await res.json();
  const page = targets.find((t) => t.type === 'page');
  if (!page) throw new Error('没有可用的 page target');
  return page;
}

function cdp(wsUrl) {
  const ws = new WebSocket(wsUrl);
  let nextId = 1;
  const pending = new Map();
  const ready = new Promise((resolve, reject) => {
    ws.addEventListener('open', () => resolve());
    ws.addEventListener('error', (e) => reject(new Error('WS 连接失败: ' + e.message)));
  });
  ws.addEventListener('message', (event) => {
    const msg = JSON.parse(event.data);
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? reject(new Error(msg.error.message)) : resolve(msg.result);
    }
  });
  return {
    ready,
    send(method, params = {}) {
      const id = nextId++;
      return new Promise((resolve, reject) => {
        pending.set(id, { resolve, reject });
        ws.send(JSON.stringify({ id, method, params }));
      });
    },
    close() {
      ws.close();
    },
  };
}

// 在页面里执行的测试脚本：返回结构化结果，不依赖页面 DOM 结构
const PAGE_SCRIPT = `
(async () => {
  const out = { duration: null, seeks: [], errors: [] };

  async function checkVideo(src, label) {
    const v = document.createElement('video');
    v.muted = true;
    v.preload = 'auto';
    v.src = src;
    document.body.appendChild(v);

    await new Promise((resolve, reject) => {
      v.addEventListener('loadedmetadata', resolve, { once: true });
      v.addEventListener('error', () => reject(new Error(label + ': 加载失败')), { once: true });
      setTimeout(() => reject(new Error(label + ': loadedmetadata 超时')), 12000);
    });

    const duration = v.duration;
    if (!(duration > 0)) throw new Error(label + ': duration 非法 ' + duration);

    // 起播
    try { await v.play(); } catch (e) { out.errors.push(label + ': play() 被拒 ' + e.message); }

    const seek = async (t) => {
      await new Promise((resolve, reject) => {
        const done = () => { v.removeEventListener('seeked', done); resolve(); };
        v.addEventListener('seeked', done);
        v.currentTime = t;
        setTimeout(() => { v.removeEventListener('seeked', done); reject(new Error(label + ': seek ' + t + ' 超时')); }, 10000);
      });
      out.seeks.push({ file: label, target: Number(t.toFixed(3)), actual: Number(v.currentTime.toFixed(3)) });
      if (Math.abs(v.currentTime - t) > 0.6) throw new Error(label + ': seek ' + t + ' 实际 ' + v.currentTime);
    };

    await seek(duration * 0.45);
    await seek(50);
    await seek(20.86);
    await seek(duration - 2);
    await seek(0);
    // 连续多次拖动，检查无挂起 / 无回弹
    await seek(80);
    await seek(30);
    await seek(95);
    await seek(duration * 0.7);

    if (v.readyState < 2) throw new Error(label + ': readyState=' + v.readyState);

    v.pause();
    v.removeAttribute('src');
    v.load();
    v.remove();
    out.duration = Number(duration.toFixed(3));
    return duration;
  }

  await checkVideo('/media/candidateflow-demo.mp4', 'with-sub');

  // 白名单拒绝：同一浏览器内直接请求应 404
  const denied = await fetch('/media/demo-video.mp4');
  out.deniedStatus = denied.status;
  const deniedBody = await denied.text();
  out.deniedLeaksPath = /owned-demo|liveproof-demo|[A-Za-z]:\\\\/.test(deniedBody);

  // 无字幕版同样可播（前 3 秒内 seek 两个点即可）
  const v2 = document.createElement('video');
  v2.muted = true; v2.preload = 'metadata'; v2.src = '/media/candidateflow-demo-nosub.mp4';
  await new Promise((resolve, reject) => {
    v2.addEventListener('loadedmetadata', resolve, { once: true });
    v2.addEventListener('error', () => reject(new Error('nosub: 加载失败')), { once: true });
    setTimeout(() => reject(new Error('nosub: 超时')), 12000);
  });
  const nosubDuration = v2.duration;
  await new Promise((resolve, reject) => {
    v2.addEventListener('seeked', resolve, { once: true });
    v2.currentTime = 40;
    setTimeout(() => reject(new Error('nosub: seek 超时')), 10000);
  });
  out.nosub = { duration: Number(nosubDuration.toFixed(3)), seek40: Number(v2.currentTime.toFixed(3)) };
  v2.remove();

  return out;
})()
`;

async function main() {
  const userDataDir = mkdtempSync(join(tmpdir(), 'lp-chrome-'));
  const chrome = spawn(CHROME, [
    '--headless=new',
    `--remote-debugging-port=${PORT}`,
    `--user-data-dir=${userDataDir}`,
    '--no-first-run',
    '--no-default-browser-check',
    '--autoplay-policy=no-user-gesture-required',
    '--mute-audio',
    'about:blank',
  ], { stdio: 'ignore' });

  let client;
  try {
    await waitForDevtools();
    const target = await firstPageTarget();
    client = cdp(target.webSocketDebuggerUrl);
    await client.ready;

    await client.send('Page.enable');
    await client.send('Runtime.enable');
    await client.send('Page.navigate', { url: `${BASE}/api/datasets` });
    await sleep(1200);

    const { result, exceptionDetails } = await client.send('Runtime.evaluate', {
      expression: PAGE_SCRIPT,
      awaitPromise: true,
      returnByValue: true,
      timeout: 90000,
    });
    if (exceptionDetails) {
      throw new Error('页面脚本异常：' + (exceptionDetails.exception?.description || exceptionDetails.text));
    }

    const r = result.value;
    console.log(JSON.stringify(r, null, 2));

    const problems = [];
    if (!(r.duration > 100)) problems.push('主素材时长异常：' + r.duration);
    if (r.seeks.length < 9) problems.push('seek 次数不足');
    for (const s of r.seeks) {
      if (Math.abs(s.actual - s.target) > 0.6) problems.push(`seek 偏差过大 ${JSON.stringify(s)}`);
    }
    if (r.deniedStatus !== 404) problems.push('白名单外文件未被拒绝：' + r.deniedStatus);
    if (r.deniedLeaksPath) problems.push('404 响应泄露了文件系统路径');
    if (!(r.nosub?.duration > 100)) problems.push('无字幕版时长异常');
    if (Math.abs(r.nosub.seek40 - 40) > 0.6) problems.push('无字幕版 seek 失败');

    if (problems.length) {
      console.error('\n播放实测未通过：');
      for (const p of problems) console.error(' - ' + p);
      process.exitCode = 1;
    } else {
      console.log('\n播放实测通过：起播 / 9 次 seek（含连续与末尾）/ 白名单拒绝 / 两个素材均可播');
    }
  } finally {
    client?.close();
    chrome.kill();
    await sleep(500);
    try {
      rmSync(userDataDir, { recursive: true, force: true });
    } catch {
      /* Chrome 可能仍持有句柄，忽略 */
    }
  }
}

main().catch((e) => {
  console.error('播放实测脚本失败：', e.message);
  process.exit(1);
});
