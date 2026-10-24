-- V8: answer_status 允许 NULL，用「空值」表示尚未核验
--
-- 问题（QA 第 3 轮 P2）：新建事项还没核验，右栏「回答状态」却已经选中「未找到回答」。
-- 根因是 V0 的列默认值即 'not_found'，加上 CaseService.create 显式塞了 NOT_FOUND，
-- 于是「尚未核验」和「核验过但视频里没找到回答」在数据层完全同形，前端只能二选一地说错话。
--
-- 口径（docs/08 四字段）：
--   review_status=unreviewed 时 answer_status 没有判定意义，必须是 NULL；
--   只有人工或 AI 采纳后显式选过 answered / partially_answered / not_found，才落下具体值。
--
-- 影响面：not_found 不再充当占位值，只表示「已判定：视频里没有找到回答」。
-- 历史数据只清理占位行，不动已有判定：
--   review_status='unreviewed' 且 answer_status='not_found' 的行视为占位，置 NULL。

ALTER TABLE cases
  MODIFY COLUMN answer_status VARCHAR(24) NULL DEFAULT NULL
    COMMENT '回答状态: answered/partially_answered/not_found；NULL 表示尚未核验';

UPDATE cases
   SET answer_status = NULL
 WHERE review_status = 'unreviewed'
   AND answer_status = 'not_found';
