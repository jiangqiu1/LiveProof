package com.liveproof.dto;

import com.liveproof.enums.SmartSubtitleTaskStatus;
import com.liveproof.enums.SubtitleSource;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 规范化后的云端字幕（时间已由毫秒转秒，且通过严格校验）。
 *
 * @param source        字幕来源，恒为 {@code cloud}；与本地 SRT 的 cue 结构完全一致，
 *                      靠该字段区分来源，而不是靠端点记忆
 * @param totalCueCount 本次从云端取回的完整条数（过滤前）
 * @param cueCount      过滤后返回的条数
 * @param windowFromSec 请求的时间窗起点；null 表示不限
 * @param windowToSec   请求的时间窗终点；null 表示不限
 * @param cues          与时间窗<b>有重叠</b>的字幕（不是「完全包含」）
 */
public record SmartSubtitleCuesDto(
        String video,
        String vid,
        String taskId,
        SubtitleSource source,
        String language,
        SmartSubtitleTaskStatus status,
        String subtitleName,
        int totalCueCount,
        int cueCount,
        Double windowFromSec,
        Double windowToSec,
        List<SubtitleCue> cues,
        LocalDateTime fetchedAt
) {
}
