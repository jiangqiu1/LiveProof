-- V5: 为 cases 增加 time_points（人工标记的关联时间点，JSON 数组，单位秒）
--
-- 背景：start_sec / end_sec 是数据源自带的演示区间，只读；
-- 核验人在回放里手动标记的补充时间点属于「人工核验结论」的一部分，
-- 原先只存在前端内存里，刷新即丢失（阶段 2 遗留问题 P1），这里给它一个持久化位置。
--
-- 存 TEXT 而非 MySQL JSON 类型：与既有 frames_json 保持一致，结构校验放在应用层
-- （CaseService：最多 20 个、0 ≤ sec ≤ 7200、两位小数、升序去重）。
--
-- 默认 NULL：未标记与「标记了空数组」语义相同，都表示没有补充时间点。
-- 本列与四个核验字段相互独立，不参与任何推导；迁移不改动既有行的其他列。

ALTER TABLE cases
  ADD COLUMN time_points TEXT NULL
    COMMENT '人工标记的关联时间点（秒），JSON 数组如 [12.5,30]；NULL 表示未标记'
    AFTER limitations;
