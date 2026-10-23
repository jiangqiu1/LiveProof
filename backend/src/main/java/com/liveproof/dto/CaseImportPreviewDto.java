package com.liveproof.dto;

import java.util.List;

/**
 * CSV 导入预览结果（<b>不写库</b>）。
 *
 * @param materialId    本次导入归属的素材
 * @param totalRows     数据行总数（不含表头）
 * @param validCount    可导入行数
 * @param invalidCount  存在问题、不会导入的行数（含与库内重复的行）
 * @param duplicateCount 与库内已有事项重复的行数（{@code normalize(question) + 素材} 相同）
 * @param rows          逐行解析结果
 * @param headers       CSV 表头原样回显，便于前端展示列映射
 * @param unmappedColumns 表头中未被识别、将被忽略的列名
 */
public record CaseImportPreviewDto(
        String materialId,
        int totalRows,
        int validCount,
        int invalidCount,
        int duplicateCount,
        List<CaseImportRow> rows,
        List<String> headers,
        List<String> unmappedColumns
) {
}