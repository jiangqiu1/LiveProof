package com.liveproof.dto;

import java.util.List;

/**
 * 字幕数据。时间为秒（number），由 SRT 的 HH:MM:SS,mmm 解析而来。
 */
public record SubtitleResponse(
        String video,
        String subtitleFile,
        String language,
        int cueCount,
        List<SubtitleCue> cues
) {
}
