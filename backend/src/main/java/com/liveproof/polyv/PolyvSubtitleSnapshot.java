package com.liveproof.polyv;

import java.util.List;

/**
 * 一次 {@code smart-subtitle/get} 的原始结果（未做秒转换、未做业务校验）。
 *
 * @param taskId       平台任务 ID
 * @param state        平台返回的进度：{@link PolyvTaskState#PROCESSING}（56812）或 READY
 * @param subtitleName 平台侧字幕名称，如「智能字幕」
 * @param link         SRT 文件地址（img.videocc.net），当前不下载，仅记录
 * @param cues         毫秒时间轴的原始字幕；PROCESSING 时为空列表
 */
public record PolyvSubtitleSnapshot(
        long taskId,
        PolyvTaskState state,
        String subtitleName,
        String link,
        List<PolyvCue> cues
) {

    public PolyvSubtitleSnapshot {
        cues = cues == null ? List.of() : List.copyOf(cues);
    }

    /** 保利威 {@code subtitles} 数组的原始条目，时间是毫秒。 */
    public record PolyvCue(long startMs, long endMs, String content) {
    }
}
