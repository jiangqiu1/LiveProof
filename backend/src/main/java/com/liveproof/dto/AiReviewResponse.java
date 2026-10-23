package com.liveproof.dto;

import java.util.List;

/**
 * AI 辅助核验的响应。
 *
 * <p>注意：接口<b>不写入数据库</b>，也不返回被改写后的用例状态。
 *
 * @param normalizationNotes 服务端对模型输出做安全规范化时的说明（被丢弃的越界时间点等）
 */
public record AiReviewResponse(
        String caseId,
        AiSuggestion suggestion,
        AiContextInfo context,
        List<String> normalizationNotes,
        String generatedAt
) {
}
