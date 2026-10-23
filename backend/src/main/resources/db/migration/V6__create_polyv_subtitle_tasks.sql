-- V6: 保利威智能字幕任务登记表
--
-- 目的：把「某个视频对应哪条云端字幕任务、现在到哪一步、最后一次成功取回是什么时候」
-- 落到数据库，避免每次请求都重新创建任务（保利威按资源点计费，重复创建是真实成本）。
--
-- 设计要点：
--   1. 一行 = 一个 (video, vid) 组合。唯一键 uk_polyv_video_vid 是「防重复创建」的
--      最终保障：应用层先查后建，并发下重复插入会被数据库拒绝，再回读既有行。
--   2. task_id 单独唯一（NULL 允许多行）：平台侧 taskId 全局唯一，不允许同一个
--      taskId 被登记到两个视频上。
--   3. 只存任务元数据，不存字幕正文。字幕正文每次按需从云端取回并校验，
--      不落库，避免云端口径变化后本地残留无法追溯的旧文本。
--   4. error_message 由应用层脱敏后写入（去掉 sign / appSecret / 32 位十六进制串），
--      长度上限 255；任何密钥都不得进入本表。
--
-- 时间列语义：
--   created_at      任务创建时间（登记既有任务时为平台侧创建时间或登记时间）
--   last_checked_at 最后一次向云端发起查询的时间
--   last_success_at 最后一次成功取回并校验通过字幕的时间

CREATE TABLE IF NOT EXISTS polyv_subtitle_tasks (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  video               VARCHAR(128) NOT NULL COMMENT '视频标识（媒体白名单文件名）',
  vid                 VARCHAR(64)  NOT NULL COMMENT '保利威视频 VID',
  task_id             VARCHAR(64)  NULL     COMMENT '智能字幕任务 ID，未创建时为 NULL',
  language            VARCHAR(32)  NOT NULL DEFAULT 'chinese' COMMENT '任务语言，按素材实际语言传',
  status              VARCHAR(24)  NOT NULL DEFAULT 'created'
    COMMENT 'created/processing/ready/failed/not_found',
  video_duration_sec  DECIMAL(10,3) NULL    COMMENT '视频时长（秒），用于字幕越界校验',
  subtitle_count      INT          NULL     COMMENT '最后一次成功取回的 cue 条数',
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_checked_at     DATETIME     NULL     COMMENT '最后一次查询云端的时间',
  last_success_at     DATETIME     NULL     COMMENT '最后一次成功取回并校验通过的时间',
  error_code          VARCHAR(32)  NULL     COMMENT '最近一次失败的错误码（脱敏）',
  error_message       VARCHAR(255) NULL     COMMENT '最近一次失败的说明（已脱敏）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_polyv_video_vid (video, vid),
  UNIQUE KEY uk_polyv_task_id (task_id),
  KEY idx_polyv_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
