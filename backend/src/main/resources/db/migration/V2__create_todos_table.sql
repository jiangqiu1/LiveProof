-- V2: 待办独立表（替代旧实现由 cases.follow_up_required 派生的伪待办）
--
-- 设计依据 docs/08 第 3.2 节。相对草案的唯一调整：
--   status / source 用 VARCHAR 而非 ENUM，与 V1 的枚举策略保持一致。
--
-- completed_at 与 status 的联动由应用层维护（TodoService.update / complete）。

CREATE TABLE IF NOT EXISTS todos (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  case_id       VARCHAR(64)  NOT NULL COMMENT '关联核验用例',
  title         VARCHAR(255) NOT NULL,
  detail        TEXT         NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'open'
    COMMENT 'open/in_progress/done/cancelled',
  source        VARCHAR(24)  NOT NULL DEFAULT 'manual'
    COMMENT 'manual/derived_from_case',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  completed_at  DATETIME     NULL DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_todos_case   (case_id),
  KEY idx_todos_status (status),
  CONSTRAINT fk_todos_case FOREIGN KEY (case_id)
    REFERENCES cases (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
