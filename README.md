# LiveProof · 视频演示证据核验

> 从回放里定位每一条产品承诺，检查它有没有被实际操作证明，并把尚未验证的问题转成待办。

LiveProof 是面向 POLYV（保利威）黑客松的参赛项目。普通 AI 视频总结回答的是「**讲了什么**」，LiveProof 回答的是「**哪一句被证明了、哪一句还欠一次演示**」。

典型场景：产品/售前 POC 演示结束后做回放核查——演示里说「支持 5000 条批量导入」，但视频里只跑了 3 条，这条承诺就不能算被证明。

---

## 核心链路

```
找到承诺  →  定位证据  →  判断充分性  →  生成待办
```

| 步骤 | 做什么 | 产出 |
| --- | --- | --- |
| 找到承诺 | 从演示视频中识别出提出的产品声称 | 核验事项（case） |
| 定位证据 | 把承诺映射到视频时间点，取出字幕片段与关键帧 | 证据上下文 |
| 判断充分性 | 人工判定：有无明确回答、有无操作演示、有无结果验证 | 四字段核验结论 |
| 生成待办 | 未验证 / 证据不足的条目转为跟进任务 | 待办事项（todo） |

## 四字段核验模型

四个字段**相互独立、不得互相推导**，任一取值的组合都**不等于「内容为真」**。`PATCH` 只更新请求中出现的字段。

| 字段 | 含义 | 取值 |
| --- | --- | --- |
| `answerStatus` | 视频里有没有针对这个问题给出回答 | `answered` / `partially_answered` / `not_found` |
| `evidenceType` | 支撑它的是什么形态的材料 | `operational_evidence` / `specification_display` / `verbal_claim` / `unknown` |
| `evidenceSufficiency` | 这些材料够不够支撑结论 | `not_checked` / `insufficient` / `sufficient` |
| `reviewStatus` | 这条事项人工核到哪一步了 | `unreviewed` / `in_review` / `reviewed` |

其中 `reviewStatus` **只有人工可以修改**，AI 无权触碰。AI 只产出建议（`AiSuggestion`），必须经「采纳并应用 + 保存」才落库。

---

## 技术栈

| 层 | 选型 |
| --- | --- |
| 后端 | Java 21 · Spring Boot 3.5.8 · MyBatis-Plus 3.5.9 · MySQL 8.4，端口 `8080` |
| 前端 | Vue 3.5 · TypeScript 5.6 · Vite 6 · Element Plus 2.9 · Pinia 2 · Vue Router 4 · Axios，端口 `5173` |
| 接口契约 | `api/openapi.yaml`（OpenAPI 3.0.3，单一事实源，25 path / 34 operation / 45 schema） |
| 数据库迁移 | `backend/src/main/resources/db/migration/V1~V7`（V7 建 `materials` 表） |
| AI | 可选。OpenAI 兼容端点（DeepSeek 官方或阿里云百炼），仅用于生成核验建议与复盘报告 |

---

## 目录结构

```
.
├── api/
│   └── openapi.yaml              接口契约（唯一事实源，改接口先改这里）
├── backend/
│   ├── pom.xml
│   └── src/main/java/com/liveproof/
│       ├── ai/                   AI 客户端、提示词、结构化解析
│       ├── config/               AiProperties / MediaProperties / PolyvProperties
│       ├── controller/           Case / Material / Media / Subtitle / Todo / Ai / Report / SmartSubtitle
│       ├── dto/                  请求与响应体
│       ├── entity/ / mapper/     实体与 MyBatis-Plus Mapper
│       ├── enums/ / exception/   枚举与统一错误码
│       ├── polyv/                保利威签名与点播客户端
│       └── service/              业务逻辑
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/         V1~V7 迁移脚本
├── frontend/
│   └── src/
│       ├── api/                  接口封装（上传走 XHR 以拿到进度）
│       ├── components/           VideoPlayer 等
│       ├── stores/               Pinia（workbench）
│       ├── views/                Workbench / Media / Records / Todos / Tasks / NewTask
│       └── types/                API 类型定义
├── scripts/                      构建、数据库与验收脚本
├── .env.example                  环境变量模板（复制为 .env.local 后填写）
├── LICENSE
└── README.md
```

> 演示媒体（`liveproof-demo/`）、过程走查产物（`_verify/`）与项目过程文档未纳入本仓库：前者含第三方版权素材且体积达数百 MB，后两者属过程产物。对外演示请使用自制的 `candidateflow-demo.mp4`。

---

## 快速开始

### 1. 前置条件

- JDK 21（本机脚本默认读取 `D:/Java/jdk-21`，可用 `JAVA_HOME` 覆盖）
- Maven 3.9+（可直接使用仓库内的 `scripts/mvn.sh`）
- Node.js 20.19+ 或 22.12+
- MySQL 8.4，需先建两个库：`liveproof`（演示库）与 `liveproof_test`（集成测试库）
- FFmpeg（可选，仅关键帧提取需要；不在 `PATH` 时用 `LIVEPROOF_FFMPEG_PATH` 指定绝对路径）

### 2. 配置环境变量

```bash
cp .env.example .env.local   # 填入真实值；.env.local 已被 .gitignore 排除，不会入库
```

必填项：

| 变量 | 用途 | 默认值 |
| --- | --- | --- |
| `MYSQL_HOST` | 数据库地址 | `127.0.0.1` |
| `MYSQL_PORT` | 数据库端口 | `3306` |
| `MYSQL_DATABASE` | 演示库名 | `liveproof` |
| `MYSQL_USER` / `MYSQL_PASSWORD` | 数据库账号 | `root` / 空 |

可选项（留空时相关功能返回明确的「未配置」错误，而不是模拟结果）：

| 变量 | 用途 |
| --- | --- |
| `POLYV_VOD_USER_ID` / `POLYV_VOD_SECRET_KEY` / `POLYV_VOD_WRITE_TOKEN` | 保利威云点播（上传、视频信息、指定时间点截图） |
| `POLYV_LIVE_APP_ID` / `POLYV_LIVE_APP_SECRET` / `POLYV_LIVE_USER_ID` | 保利威 v4 智能字幕 |
| `DEEPSEEK_API_KEY` / `DEEPSEEK_BASE_URL` / `DEEPSEEK_MODEL` | AI 辅助核验与复盘报告 |
| `AI_MAX_TOKENS` / `AI_TIMEOUT_MS` / `AI_MAX_RETRIES` / `AI_MAX_FRAMES` / `AI_FRAME_MAX_WIDTH` | AI 调用与抽帧参数 |
| `LIVEPROOF_FFMPEG_PATH` | FFmpeg 绝对路径 |
| `LIVEPROOF_MEDIA_ROOT` / `LIVEPROOF_MEDIA_UPLOAD_DIR` | 内置素材根目录与上传临时目录 |

### 3. 初始化数据库

```bash
scripts/db-migrate.sh          # 执行 V1~V7 迁移
scripts/db-verify-backup.sh    # 可选：校验备份可用性
```

### 4. 构建并启动

```bash
# 后端（8080）
scripts/mvn.sh -f backend/pom.xml clean package
scripts/run-backend.sh

# 前端（5173）
cd frontend && npm install && npm run dev
```

> 注意：`package` 前请先停止占用 8080 的进程，否则 jar 被占用会导致构建失败；必须使用 `clean package`，增量构建可能残留过期 class。

### 5. 验收

```bash
cd frontend && npm run typecheck          # vue-tsc --noEmit
scripts/verify-new-task-flow.mjs          # 核验任务创建向导 E2E
scripts/verify-media-playback.mjs         # 媒体播放与 Range 请求
node scripts/reset-demo-data.mjs          # 重置演示数据（默认 dry-run，加 --apply 才执行）
```

后端集成测试共 **211 条**（`Tests run: 211, Failures: 0, Errors: 0, Skipped: 3`，3 条 skip 为依赖 FFmpeg 的用例）：

```bash
scripts/mvn.sh -f backend/pom.xml test
```

---

## 接口概览

所有错误统一为 `{"error":{"code":"...","message":"..."}}`，校验类错误额外带 `details`。完整定义见 `api/openapi.yaml`。

| 分组 | 主要端点 |
| --- | --- |
| 核验事项 | `GET /api/cases`、`POST /api/cases`、`GET /api/cases/{id}`、`PATCH /api/cases/{id}`、`DELETE /api/cases/{id}` |
| 待办 | `GET /api/todos`、`POST /api/todos`、`PATCH /api/todos/{id}`、`DELETE /api/todos/{id}` |
| 素材 | `GET /api/materials`、`POST /api/materials`、`GET /api/materials/capabilities`、`POST /api/materials/{id}/play`、`DELETE /api/materials/{id}` |
| 字幕 | `GET /api/subtitles/...`、`POST /api/materials/{id}/subtitle`、`DELETE /api/materials/{id}/subtitle` |
| 智能字幕 | `POST /api/smart-subtitles/tasks`、`GET /api/smart-subtitles/tasks/{taskId}`、`POST /api/smart-subtitles/tasks/adopt` |
| AI | `POST /api/ai/suggestions`、`POST /api/ai/review-summary` |
| 报告 | `GET /api/reports/...` |
| 媒体 | `GET /media/{name}`（手写 Range 支持，返回 200 / 206 / 416） |

---

## 已知边界

这些是产品如实呈现的证据边界，不是缺陷：

- 自制演示素材存在约 52 秒（31%）无字幕区间，该区间只能标为「未充分检查」。
- 保利威指定时间点截图为**云端整秒精度**，与请求时间点可能存在偏差。
- `status=50` 时 `transcodeInfos[].status` 为 `unavailable`，但 `playUrl` 已可播放，取流逻辑已做退化处理。
- AI 建议可能误读数字，采纳前须人工核对。

---

## 许可证

[MIT](./LICENSE)
