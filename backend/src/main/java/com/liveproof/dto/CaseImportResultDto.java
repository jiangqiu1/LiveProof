package com.liveproof.dto;

import java.util.List;

/**
 * CSV 导入执行结果。
 *
 * <p>两档策略只影响「有非法行时怎么办」，不影响「合法行是否入账」：
 * <ul>
 *   <li>{@code strict}（默认）：存在任何非法行则整批拒绝，一条都不写</li>
 *   <li>{@code skip_invalid}：跳过非法行，写入合法行，并在 {@code skipped} 里如实列出</li>
 * </ul>
 * 两档都<b>不</b>静默忽略问题行。
 *
 * @param materialId 归属素材
 * @param imported   实际写入的条数
 * @param skipped    跳过的行号与原因（strict 下拒绝时会包含全部非法行）
 * @param created    已写入的事项
 * @param policy     本次采用的策略
 */
public record CaseImportResultDto(
        String materialId,
        int imported,
        int skippedCount,
        List<SkippedRow> skipped,
        List<CaseListItem> created,
        String policy
) {

    /** 被跳过的行。 */
    public record SkippedRow(int line, List<String> problems) {
    }
}