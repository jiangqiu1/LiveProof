package com.liveproof.dto;

import com.liveproof.enums.TodoSource;
import com.liveproof.enums.TodoStatus;

import java.time.LocalDateTime;

/**
 * 待办响应。时间字段为 ISO-8601 本地时间（不带时区后缀）。
 */
public record TodoDto(
        Long id,
        String caseId,
        String title,
        String detail,
        TodoStatus status,
        TodoSource source,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt
) {
}
