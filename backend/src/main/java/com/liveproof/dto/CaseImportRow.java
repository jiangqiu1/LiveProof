package com.liveproof.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * CSV 导入的单行解析与校验结果。
 *
 * @param line      CSV 行号（含表头，从 1 开始，便于用户在表格软件里定位）
 * @param question  待核验问题（已 trim；解析失败时为 null）
 * @param claim     产品承诺（已 trim）
 * @param startSec  区间起点（秒）；未填为 null
 * @param endSec    区间终点（秒）；未填为 null
 * @param problems  该行的问题列表；<b>空列表表示该行可以导入</b>
 */
public record CaseImportRow(
        int line,
        String question,
        String claim,
        BigDecimal startSec,
        BigDecimal endSec,
        List<String> problems
) {

    public boolean valid() {
        return problems == null || problems.isEmpty();
    }
}