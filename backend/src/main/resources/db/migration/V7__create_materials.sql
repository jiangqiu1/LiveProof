-- V7：素材库（保利威点播为主存储）+ 核验事项归属/来源/创建时间
--
-- 约束（勿改）：
--   1. 本脚本只新增结构 + 2 行内置素材登记，**不得插入任何 cases 行**。
--      CaseApiTest hasSize(6) / ReportApiTest total=6 依赖 cases 行数不变。
--   2. 内置素材 storage='local'，polyv_vid 留 NULL：VID 仍由 application.yml 的
--      video-vids 静态配置提供（测试库用 testvid_0001 覆盖），避免两套事实。
--   3. 历史 cases 行的 created_at 保持 NULL，不伪造创建时间。

CREATE TABLE IF NOT EXISTS materials (
  id             VARCHAR(40)  NOT NULL COMMENT '对外唯一标识 mat_<hex>',
  storage        VARCHAR(16)  NOT NULL DEFAULT 'polyv' COMMENT 'local=本机内置 / polyv=保利威点播',
  polyv_vid      VARCHAR(64)  NULL     COMMENT 'polyv 素材必填；local 且不接云端字幕时为空',
  file_name      VARCHAR(128) NULL     COMMENT 'local 素材的服务端文件名；polyv 为 NULL',
  original_name  VARCHAR(180) NOT NULL COMMENT '用户原始文件名，仅展示，永不参与路径拼接',
  srt_file_name  VARCHAR(128) NULL     COMMENT '本机字幕文件名；空=无本地字幕',
  duration_sec   DECIMAL(10,3) NULL    COMMENT '云端为整数秒，本地为 FFmpeg 两位小数',
  polyv_status   INT          NULL     COMMENT '原始状态码 10/20/40/50/51/60/61/-1',
  size_bytes     BIGINT       NULL,
  width          INT          NULL,
  height         INT          NULL,
  status         VARCHAR(24)  NOT NULL DEFAULT 'transcoding'
    COMMENT 'transcoding/ready/failed/deleted/rejected；50 等待审核仍算 ready（playUrl 可播）',
  builtin        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=内置示例，禁止删除',
  error_message  VARCHAR(255) NULL     COMMENT '已脱敏的失败原因',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_materials_vid (polyv_vid),
  UNIQUE KEY uk_materials_file (file_name),
  UNIQUE KEY uk_materials_srt (srt_file_name),
  KEY idx_materials_status (status),
  KEY idx_materials_storage (storage)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE cases
  ADD COLUMN material_id VARCHAR(40) NULL COMMENT '归属素材；历史行回填 mat_builtin_demo' AFTER video,
  ADD COLUMN created_at  DATETIME    NULL COMMENT 'NULL=历史数据，不伪造' AFTER updated_at,
  ADD COLUMN source      VARCHAR(24) NOT NULL DEFAULT 'sample' COMMENT 'sample/manual/csv',
  ADD KEY idx_cases_material (material_id);

INSERT INTO materials (id, storage, polyv_vid, file_name, original_name, duration_sec, status, builtin)
VALUES
  ('mat_builtin_demo',       'local', NULL, 'candidateflow-demo.mp4',       'candidateflow-demo.mp4',       106.470, 'ready', 1),
  ('mat_builtin_demo_nosub', 'local', NULL, 'candidateflow-demo-nosub.mp4', 'candidateflow-demo-nosub.mp4', 106.470, 'ready', 1);

UPDATE cases SET material_id = 'mat_builtin_demo' WHERE video = 'candidateflow-demo.mp4';
