package com.liveproof.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建智能字幕任务请求。
 *
 * <p>没有 {@code vid} 字段：VID 只能来自服务端配置（视频标识 -> VID 映射），
 * 客户端不能指定，避免把任意视频的识别额度挂到本账号上。
 */
public record SmartSubtitleCreateRequest(
        @NotBlank(message = "video 不能为空")
        @Size(max = 128, message = "video 长度不能超过 128")
        String video,

        /** 可选；缺省用服务端配置的默认语言。必须是受支持的语言，不做猜测。 */
        @Size(max = 32, message = "language 长度不能超过 32")
        String language
) {
}
