package com.liveproof.dto;

import java.math.BigDecimal;

/**
 * 本次提交给模型的字幕片段。
 *
 * @param outsideWindow 是否位于事项原始时间区间之外（服务端计算）
 */
public record AiCueRef(
        int index,
        BigDecimal startSec,
        BigDecimal endSec,
        String text,
        boolean outsideWindow
) {
}
