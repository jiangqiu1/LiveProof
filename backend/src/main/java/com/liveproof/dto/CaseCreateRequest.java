package com.liveproof.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 手动新增核验事项。
 *
 * <p>三条硬约束（与产品红线一致）：
 * <ul>
 *   <li>{@code materialId} 必填：归属由当前选中的素材决定，客户端<b>不能</b>自带 {@code video}</li>
 *   <li>{@code reviewStatus} / {@code source} / {@code id} / {@code createdAt} 一律服务端决定，
 *       不接受客户端指定「已核验」</li>
 *   <li>{@code answerStatus} 落数据库默认值 {@code not_found}，但它只是<b>占位值</b>，
 *       前端必须显示为「尚未核验」，不得当作「视频没有这样说」</li>
 * </ul>
 */
public record CaseCreateRequest(
        String materialId,
        String question,
        String claim,
        BigDecimal startSec,
        BigDecimal endSec,
        String reasoning,
        String limitations,
        List<BigDecimal> timePoints
) {

    /** 问题与承诺的长度上限。 */
    public static final int MAX_TITLE_LENGTH = 500;

    /** 预设窗口上界（秒），与 timePoints 同一口径。 */
    public static final BigDecimal MAX_SEC = BigDecimal.valueOf(7200);
}
