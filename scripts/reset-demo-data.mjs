// 演示数据复位：清掉录制/实测过程中产生的素材与核验事项，回到可重复演示的基线。
//
// 为什么需要它：录制视频要重来十几次，每次都会留下「上传的素材 + 新增/导入的事项 + 待办 + AI 建议」。
// 手工清理容易漏，且一旦误删内置示例（6 条基线用例 + 2 个内置素材）整个演示就废了。
//
// 硬保护（无论何种参数都不会动）：
//   1. `builtin=true` 的素材（mat_builtin_demo / mat_builtin_demo_nosub）
//   2. `source=sample` 的基线 6 条核验事项
//   3. 所有 builtin 素材上的待办
//
// 用法：
//   node scripts/reset-demo-data.mjs              # 只预览，不删任何东西（默认）
//   node scripts/reset-demo-data.mjs --apply      # 真正执行删除
//   node scripts/reset-demo-data.mjs --apply --keep-cases   # 只删素材，保留事项
//
// 依赖：后端已在 8080 运行（只走 API，不直连数据库，避免凭据外泄）。

const BASE = process.env.LIVEPROOF_API || 'http://127.0.0.1:8080';
const APPLY = process.argv.includes('--apply');
const KEEP_CASES = process.argv.includes('--keep-cases');
const KEEP_MATERIALS = process.argv.includes('--keep-materials');

const log = (...args) => console.log(...args);

async function api(path, options = {}) {
  const res = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options,
  });
  const text = await res.text();
  let body = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = text;
    }
  }
  if (!res.ok) {
    throw new Error(`${options.method || 'GET'} ${path} -> HTTP ${res.status} ${text.slice(0, 200)}`);
  }
  return body;
}

/** 把失败当作可继续：录制现场不希望因为一条删不掉就整体中断。 */
async function tryApi(path, options) {
  try {
    return { ok: true, body: await api(path, options) };
  } catch (err) {
    return { ok: false, error: err.message };
  }
}

async function main() {
  log(`目标后端：${BASE}`);
  log(`模式：${APPLY ? '执行删除' : '仅预览（加 --apply 才会真的删）'}`);
  log('');

  const materials = await api('/api/materials');
  const cases = await api('/api/cases');
  const todos = await api('/api/todos');

  // ── 分类：可删 / 受保护 ──────────────────────────────
  const builtinMaterials = materials.filter((m) => m.builtin);
  const customMaterials = materials.filter((m) => !m.builtin);
  const builtinIds = new Set(builtinMaterials.map((m) => m.id));

  const sampleCases = cases.filter((c) => c.source === 'sample' || builtinIds.has(c.materialId));
  const customCases = cases.filter((c) => c.source !== 'sample' && !builtinIds.has(c.materialId));

  const protectedTodoIds = new Set(
    todos.filter((t) => builtinIds.has(t.materialId ?? 'mat_builtin_demo')).map((t) => t.id),
  );
  const customTodos = todos.filter((t) => !protectedTodoIds.has(t.id));

  log('── 现状 ─────────────────────────────');
  log(`素材 ${materials.length} 个：内置 ${builtinMaterials.length}（保护）、自定义 ${customMaterials.length}`);
  log(`事项 ${cases.length} 条：基线 sample ${sampleCases.length}（保护）、新增 ${customCases.length}`);
  log(`待办 ${todos.length} 条：可删 ${customTodos.length}`);
  log('');

  log('── 受保护（不会删除）────────────────');
  for (const m of builtinMaterials) log(`  素材 ${m.id}  ${m.fileName ?? m.originalName}`);
  for (const c of sampleCases) log(`  事项 ${c.id}  ${c.question?.slice(0, 24)}`);
  if (builtinMaterials.length === 0 || sampleCases.length === 0) {
    log('  !! 警告：没有找到内置素材或基线事项，检查库是否被改动过');
  }
  log('');

  log('── 将被清理 ─────────────────────────');
  if (KEEP_CASES) {
    log('  事项：按 --keep-cases 跳过');
  } else {
    for (const c of customCases) log(`  事项 ${c.id}  source=${c.source}  ${c.question?.slice(0, 24)}`);
    if (customCases.length === 0) log('  （无）');
  }
  if (KEEP_MATERIALS) {
    log('  素材：按 --keep-materials 跳过');
  } else {
    for (const m of customMaterials) log(`  素材 ${m.id}  ${m.originalName ?? m.fileName}  status=${m.status}`);
    if (customMaterials.length === 0) log('  （无）');
  }
  for (const t of customTodos) log(`  待办 #${t.id}  ${t.title?.slice(0, 30)}`);
  if (customTodos.length === 0) log('  （无待办）');
  log('');

  if (!APPLY) {
    log('预览结束。确认无误后加 --apply 执行。');
    return;
  }

  // ── 删除顺序：待办 → 事项 → 素材（素材被事项引用时后端会拒绝删除）──
  let deletedTodos = 0;
  let failedTodos = 0;
  for (const t of customTodos) {
    const r = await tryApi(`/api/todos/${t.id}`, { method: 'DELETE' });
    if (r.ok) deletedTodos += 1;
    else failedTodos += 1;
  }

  let deletedCases = 0;
  const failedCases = [];
  if (!KEEP_CASES) {
    for (const c of customCases) {
      const r = await tryApi(`/api/cases/${c.id}`, { method: 'DELETE' });
      if (r.ok) deletedCases += 1;
      else failedCases.push(`${c.id}: ${r.error}`);
    }
  }

  let deletedMaterials = 0;
  const failedMaterials = [];
  if (!KEEP_MATERIALS) {
    for (const m of customMaterials) {
      const r = await tryApi(`/api/materials/${m.id}`, { method: 'DELETE' });
      if (r.ok) deletedMaterials += 1;
      else failedMaterials.push(`${m.id}: ${r.error}`);
    }
  }

  // ── 复检：基线必须还在 ────────────────────────────────
  const afterMaterials = await api('/api/materials');
  const afterCases = await api('/api/cases');
  const afterBuiltin = afterMaterials.filter((m) => m.builtin).length;
  const afterSample = afterCases.filter((c) => c.source === 'sample').length;

  log('── 结果 ─────────────────────────────');
  log(`已删除：待办 ${deletedTodos}、事项 ${deletedCases}、素材 ${deletedMaterials}`);
  if (failedTodos) log(`待办删除失败 ${failedTodos} 条（多为已不存在）`);
  for (const f of failedCases) log(`  事项删除失败 ${f}`);
  for (const f of failedMaterials) log(`  素材删除失败 ${f}`);
  log('');
  log(`复检：内置素材 ${afterBuiltin} 个（应为 ${builtinMaterials.length}）、基线 sample 事项 ${afterSample} 条（应为 ${sampleCases.length}）`);

  const baselineIntact = afterBuiltin === builtinMaterials.length && afterSample === sampleCases.length;
  const cleanDone = failedCases.length === 0 && failedMaterials.length === 0;
  log('');
  log(`RESULT: ${baselineIntact && cleanDone ? 'OK' : 'NEED_ATTENTION'}`);
  if (!baselineIntact) {
    log('  !! 基线数据发生变化，请不要开始录制，先排查');
  }
  if (failedMaterials.length) {
    log('  提示：素材删除失败通常是仍被核验事项引用，先删事项（或去掉 --keep-cases）再重跑');
  }
}

main().catch((err) => {
  console.error('RESET_FAILED:', err.message);
  process.exitCode = 1;
});