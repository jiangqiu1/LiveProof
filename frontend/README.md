# LiveProof 前端（Vue 3 + TypeScript + Vite）

三栏核验工作台：左栏真实用例、中栏视频与字幕、右栏四字段改判。

## 启动

```bash
# 后端先行（仓库根目录）
scripts/run-backend.sh              # http://127.0.0.1:8080

# 前端
cd frontend
npm install
npm run dev                         # http://127.0.0.1:5173
```

`/api` 与 `/media` 由 Vite 代理到 `http://127.0.0.1:8080`，页面不直接读磁盘文件。
换后端地址：`LIVEPROOF_BACKEND=http://127.0.0.1:8081 npm run dev`。

## 构建

```bash
npm run build      # vue-tsc 类型检查 + vite build → dist/
npm run preview    # 4173，代理规则与 dev 一致
```

## 端到端验证

```bash
# 仓库根目录，需前后端都已启动
node scripts/verify-frontend.mjs
```

真实 Chrome 通过 CDP 驱动，检查用例渲染、视频拖动、字幕定位、四字段保存、
刷新恢复、待办创建与完成、桌面布局溢出；截图输出到 `frontend/verify-shots/`。

## 页面

| 路径 | 说明 |
|---|---|
| `/` | 工作台（三栏核验，支持 `?case=<id>`） |
| `/materials` | 视频素材与字幕来源 |
| `/records` | 核验记录总表 |
| `/todos` | 待办复盘 |

> 素材页路径是 `/materials`：`/media` 已被后端视频流代理占用，同名会让整页导航被代理接管。

## 设计规范

页面背景 `#F7FAF9`、品牌薄荷绿 `#00E5A8`、深墨绿 `#081A26`、边框 `#E5ECE9`、
次级文字 `#71817C`、警示橙 `#FFB454`。令牌集中在 `src/styles/main.css` 的 `:root`。
