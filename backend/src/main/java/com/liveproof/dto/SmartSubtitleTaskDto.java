package com.liveproof.dto;

import com.liveproof.enums.SmartSubtitleTaskStatus;

import java.time.LocalDateTime;

/**
 * 智能字幕任务登记（本地持久化视图）。
 *
 * <p>{@code errorMessage} 已在服务端脱敏（去掉 sign / appSecret / 32 位十六进制串），
 * 且不含任何请求体与凭据。
 *
 * @param reused 本次操作是否复用了既有任务：创建/登记接口会填该字段，
 *               其余接口为 null（不代表任何语义）
 */
public record SmartSubtitleTaskDto(
        Long id,
        String video,
        String vid,
        String taskId,
        String language,
        SmartSubtitleTaskStatus status,
        Double videoDurationSec,
        Integer subtitleCount,
        LocalDateTime createdAt,
        LocalDateTime lastCheckedAt,
        LocalDateTime lastSuccessAt,
        String errorCode,
        String errorMessage,
        Boolean reused
) {
}
