package com.liveproof.dto;

import java.util.List;

/**
 * 候选事项确认入库的结果。
 *
 * <p>{@code requested} 与 {@code createdCount} 相等才代表全部写入；
 * 接口是「要么全成功、要么整批拒绝」的语义，不会出现部分写入。
 *
 * @param requested    提交的条数
 * @param createdCount 实际写入的条数
 * @param created      写入后的事项详情（顺序与提交一致）
 * @param source       来源标记，恒为 {@code ai}，用于复盘报告区分人工录入与 AI 起草
 */
public record CaseCandidateBatchResult(
        String materialId,
        int requested,
        int createdCount,
        List<CaseDetail> created,
        String source
) {
}
