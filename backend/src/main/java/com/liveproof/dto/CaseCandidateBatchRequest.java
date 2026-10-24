package com.liveproof.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 把候选事项确认入库。
 *
 * <p>为什么单独做一个批量端点，而不是前端循环调 {@code POST /api/cases}：
 * <ul>
 *   <li>一次确认必须是<b>原子</b>的——不能出现「前 3 条进去了、第 4 条失败」，让用户不知道到底录了几条</li>
 *   <li>来源要统一记成 {@code ai}，便于复盘报告区分「AI 帮你想的」与「你自己写的」</li>
 * </ul>
 *
 * <p>与 CSV 导入的 strict 语义一致：<b>任何一条非法就整批拒绝</b>，
 * 并把每条的问题一起返回，用户改完再提交，而不是悄悄少录几条。
 */
public record CaseCandidateBatchRequest(
        String materialId,
        List<Item> items
) {

    /** 单次最多确认条数，防止一次塞入过多数据。 */
    public static final int MAX_ITEMS = 20;

    public record Item(
            String question,
            String claim,
            BigDecimal startSec,
            BigDecimal endSec
    ) {
    }
}
