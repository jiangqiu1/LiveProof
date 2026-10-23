-- V4: 把历史 follow_up_required = 1 的用例派生为真实待办记录（一次性、幂等）
--
-- 这是数据迁移，不是运行时派生：迁移后待办有独立 id、状态与时间戳，可独立编辑与完成。
-- 当前 cf 数据集 6 条 follow_up_required 全为 0，执行后影响行数为 0（符合预期，不造假数据）。

INSERT INTO todos (case_id, title, detail, status, source)
SELECT c.id,
       LEFT(COALESCE(NULLIF(c.follow_up_task, ''), c.question), 255),
       NULLIF(c.follow_up_task, ''),
       'open',
       'derived_from_case'
FROM cases c
WHERE c.follow_up_required = 1
  AND NOT EXISTS (
    SELECT 1 FROM todos t
    WHERE t.case_id = c.id AND t.source = 'derived_from_case'
  );
