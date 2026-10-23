package com.liveproof.dto;

import java.math.BigDecimal;

/**
 * 本次提交给模型的关键帧。
 *
 * @param dataUrl 缩略图的 data URL（供前端核对「模型到底看了哪一帧」）；未回传时为 null
 */
public record AiFrameRef(
        BigDecimal sec,
        Integer width,
        Integer height,
        Integer bytes,
        String dataUrl
) {
}
