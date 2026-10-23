-- V3: 回填四字段（从旧 evidence_status 与 raw_json 还原，严禁推断）
--
-- 关键点：数据库里的 evidence_status 已被旧 Node 实现归一化，丢失了"部分证据"的原始信息。
-- cf 的 EVIDENCE_PARTIAL 在旧实现中被映射成了 specification_display（见 web/normalize.js EVIDENCE_MAP），
-- 因此**不能**只按 evidence_status 回填，必须优先从 raw_json 读回原始取值。
--
-- 映射表（docs/08 2.1-2.3）：
--   cf EVIDENCE_SHOWN    -> operational_evidence / sufficient
--   cf EVIDENCE_PARTIAL  -> unknown / not_checked        （禁止推断，见 docs/08 2.3）
--   cf EVIDENCE_ABSENT   -> unknown / insufficient
--   lc operational_evidence  -> operational_evidence / sufficient
--   lc specification_display -> specification_display / not_checked
--   lc verbal_claim          -> verbal_claim / not_checked
--   lc insufficient          -> unknown / insufficient
--
-- 幂等保护：只回填 evidence_type 仍为 NULL 的行；review_status 一律保持 unreviewed。

UPDATE cases
SET
  evidence_type = CASE
    WHEN ds = 'cf' AND raw_json IS NOT NULL THEN
      CASE UPPER(JSON_UNQUOTE(JSON_EXTRACT(raw_json, '$.expectedEvidenceStatus')))
        WHEN 'EVIDENCE_SHOWN'   THEN 'operational_evidence'
        WHEN 'EVIDENCE_PARTIAL' THEN 'unknown'
        WHEN 'EVIDENCE_ABSENT'  THEN 'unknown'
        ELSE 'unknown'
      END
    ELSE
      CASE evidence_status
        WHEN 'operational_evidence'  THEN 'operational_evidence'
        WHEN 'specification_display' THEN 'specification_display'
        WHEN 'verbal_claim'          THEN 'verbal_claim'
        ELSE 'unknown'
      END
  END,
  evidence_sufficiency = CASE
    WHEN ds = 'cf' AND raw_json IS NOT NULL THEN
      CASE UPPER(JSON_UNQUOTE(JSON_EXTRACT(raw_json, '$.expectedEvidenceStatus')))
        WHEN 'EVIDENCE_SHOWN'   THEN 'sufficient'
        WHEN 'EVIDENCE_ABSENT'  THEN 'insufficient'
        ELSE 'not_checked'
      END
    ELSE
      CASE evidence_status
        WHEN 'operational_evidence' THEN 'sufficient'
        WHEN 'insufficient'         THEN 'insufficient'
        ELSE 'not_checked'
      END
  END,
  review_status = 'unreviewed'
WHERE evidence_type IS NULL
  AND review_status = 'unreviewed';
