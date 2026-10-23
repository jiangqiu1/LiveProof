package com.liveproof.dto;

import com.liveproof.enums.SubtitleSource;

import java.util.List;

/**
 * 本次分析的输入上下文，用于让用户核对 AI 到底看了什么。
 *
 * @param subtitleSource 本次定位所用的字幕来源（local / cloud），与字幕文件字段配套出现
 * @param durationSec   视频时长（秒）；未知时为 null
 * @param cuesUsed      实际提交给模型的字幕片段
 * @param frames        实际提交给模型的关键帧（含缩略图 data URL）
 * @param visionUsed    本次是否真的把画面交给了模型
 * @param frameNote     未使用画面的原因（例如未找到 FFmpeg / 该视频不在白名单）
 * @param outsideWindowSearched 是否在原始时间区间之外检索过相关内容
 */
public record AiContextInfo(
        String video,
        SubtitleSource subtitleSource,
        Double durationSec,
        String subtitleFile,
        int totalCueCount,
        List<AiCueRef> cuesUsed,
        List<AiFrameRef> frames,
        boolean visionUsed,
        String frameNote,
        boolean outsideWindowSearched,
        String model,
        int promptChars,
        Integer promptTokens,
        Integer completionTokens,
        long elapsedMs
) {
}
