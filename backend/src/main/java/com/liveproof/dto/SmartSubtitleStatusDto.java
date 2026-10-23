package com.liveproof.dto;

import java.util.List;

/**
 * 保利威智能字幕接入状态（只读，不触发任何云端调用）。
 *
 * <p>不含 appId / appSecret / sign，只说明「配没配、映射到哪个 VID、时长基准是多少」。
 */
public record SmartSubtitleStatusDto(
        boolean configured,
        String baseUrlHost,
        String defaultLanguage,
        List<String> allowedLanguages,
        List<VideoTarget> videos,
        String note
) {

    /**
     * @param video         视频标识（媒体白名单文件名）
     * @param vid           保利威 VID；未配置为 null
     * @param durationSec   时长基准（秒）；未配置为 null（此时无法做越界校验）
     * @param vidConfigured 是否已配置 VID
     */
    public record VideoTarget(String video, String vid, Double durationSec, boolean vidConfigured) {
    }
}
