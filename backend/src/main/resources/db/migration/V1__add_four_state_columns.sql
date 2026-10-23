-- V1: 为 cases 增加四类核验字段（新增列，全部可空或带安全默认值，不触碰既有数据）
--
-- 设计依据 docs/08 第 1 节与第 5.5 节：
--   采用 VARCHAR + Java enum，而非 MySQL ENUM，避免后续取值调整需要 ALTER TABLE。
--   枚举合法值由应用层（Jakarta Validation / EnumSupport）强制，非法值在接口层被拒绝。
--
-- 红线：review_status 默认 unreviewed。迁移不等于已复核，禁止批量标 reviewed。

ALTER TABLE cases
  ADD COLUMN review_status VARCHAR(16) NOT NULL DEFAULT 'unreviewed'
    COMMENT '流程进度: unreviewed/in_review/reviewed'
    AFTER evidence_status,
  ADD COLUMN evidence_type VARCHAR(32) NULL
    COMMENT '证据形态: verbal_claim/specification_display/operational_evidence/unknown'
    AFTER review_status,
  ADD COLUMN evidence_sufficiency VARCHAR(16) NOT NULL DEFAULT 'not_checked'
    COMMENT '证据充分性: not_checked/insufficient/sufficient'
    AFTER evidence_type;
