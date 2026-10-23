package com.liveproof.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 登记既有智能字幕任务（例如阶段一审计中已真实创建的 taskId）。
 *
 * <p>用途：不新建任务也能把已知任务纳入系统管理。服务端会先用只读的
 * {@code smart-subtitle/get} 校验该任务确实存在，通过后才写库。
 */
public record SmartSubtitleAdoptRequest(
        @NotBlank(message = "video 不能为空")
        @Size(max = 128, message = "video 长度不能超过 128")
        String video,

        @NotBlank(message = "taskId 不能为空")
        @Pattern(regexp = "^[0-9]{1,19}$", message = "taskId 必须是纯数字")
        String taskId,

        @Size(max = 32, message = "language 长度不能超过 32")
        String language,

        /**
         * 可选：平台侧任务创建时间（登记时用于补齐 created_at）。
         * 平台接口不返回该字段，只能由登记方提供；缺省取当前时间。
         */
        LocalDateTime createdAt
) {
}
