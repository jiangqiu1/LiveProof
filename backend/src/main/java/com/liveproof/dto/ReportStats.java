package com.liveproof.dto;

/**
 * 复盘报告的统计数字。全部由后端按数据库真实数据计算，模型不得参与生成。
 *
 * @param reviewed     reviewStatus = reviewed 的条数
 * @param unreviewed   reviewStatus = unreviewed 的条数（报告中必须保持为未检查）
 * @param inReview     reviewStatus = in_review 的条数
 * @param answered     回答状态为 answered 的条数
 * @param partial      回答状态为 partially_answered 的条数
 * @param notFound     回答状态为 not_found 的条数
 * @param operational  证据形态为 operational_evidence 的条数
 */
public record ReportStats(
        int total,
        int reviewed,
        int inReview,
        int unreviewed,
        int answered,
        int partial,
        int notFound,
        int operational,
        int specificationDisplay,
        int verbalClaim,
        int evidenceUnknown,
        int sufficient,
        int insufficient,
        int notChecked,
        int todoTotal,
        int todoOpen,
        int todoInProgress,
        int todoDone,
        int todoCancelled
) {
}
