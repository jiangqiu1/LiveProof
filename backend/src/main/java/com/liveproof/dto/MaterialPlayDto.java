package com.liveproof.dto;

import java.math.BigDecimal;

/**
 * 播放信息。
 *
 * <p>{@code playUrl} 是保利威 CDN 直链（当前账号的点播视频未加密），**不落库**、每次实时取，
 * 避免 CDN 域名或清晰度档位变化后返回过期地址。
 *
 * @param published 是否已发布；false 表示仍在保利威审核中（playUrl 可用但未正式发布）
 */
public record MaterialPlayDto(
        String materialId,
        String playUrl,
        BigDecimal durationSec,
        boolean published
) {
}
