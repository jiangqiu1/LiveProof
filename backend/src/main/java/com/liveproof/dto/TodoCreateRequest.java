package com.liveproof.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建待办请求。caseId 必须已存在于 cases 表。
 */
public record TodoCreateRequest(
        @NotBlank(message = "caseId 不能为空")
        @Size(max = 64, message = "caseId 长度不能超过 64")
        String caseId,

        @NotBlank(message = "title 不能为空")
        @Size(max = 255, message = "title 长度不能超过 255")
        String title,

        @Size(max = 5000, message = "detail 长度不能超过 5000")
        String detail
) {
}
