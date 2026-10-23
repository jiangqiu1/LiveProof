package com.liveproof.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 素材对外视图。
 *
 * <p>不含任何绝对路径、目录、密钥；{@code originalName} 仅用于展示，永不参与路径拼接。
 *
 * @param storage   local（内置示例，走 /media 与 FFmpeg）/ polyv（保利威点播）
 * @param status    transcoding / ready / failed / deleted
 * @param published 保利威是否已发布（60/61）；{@code status=50 等待审核} 时为 false，
 *                  但 playUrl 已可播，前端据此提示而不是阻塞
 */
public record MaterialDto(
        String id,
        String storage,
        String originalName,
        String fileName,
        boolean builtin,
        String status,
        Integer polyvStatus,
        boolean published,
        BigDecimal durationSec,
        Long sizeBytes,
        Integer width,
        Integer height,
        String srtFileName,
        LocalDateTime createdAt
) {
}
