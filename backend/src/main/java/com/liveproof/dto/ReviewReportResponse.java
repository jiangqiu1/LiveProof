package com.liveproof.dto;

import java.util.List;

/**
 * 复盘报告（服务端即时生成，不落库）。
 *
 * @param aiUsed 本次是否成功使用了模型归纳（false 表示报告仅含可追溯事实）
 * @param notes  生成过程中的说明：AI 失败原因、被过滤的表述等
 */
public record ReviewReportResponse(
        String datasetId,
        String datasetLabel,
        String generatedAt,
        ReportStats stats,
        boolean aiUsed,
        String aiModel,
        List<String> notes,
        String markdown
) {
}
