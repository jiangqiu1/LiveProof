package com.liveproof.dto;

import java.util.List;

/**
 * 字幕数据。时间为秒（number），由 SRT 的 HH:MM:SS,mmm 解析而来。
 *
 * @param source 这份字幕是从哪来的（{@link #SOURCE_UPLOADED} 等）。
 *               素材页曾只按 {@code materials.srt_file_name} 判断「有没有字幕」，
 *               而工作台直接用本接口的 cues，导致「素材页显示未上传、核验时却有 12 条字幕」
 *               两个口径互相矛盾。把来源如实返回，前端才能说清「没上传」不等于「没字幕」。
 */
public record SubtitleResponse(
        String video,
        String subtitleFile,
        String source,
        String language,
        int cueCount,
        List<SubtitleCue> cues
) {

    /** 用户在素材页显式上传、并登记到 {@code materials.srt_file_name} 的字幕 */
    public static final String SOURCE_UPLOADED = "uploaded";

    /** 静态白名单映射：内置示例素材配套的 SRT（{@code liveproof.media.subtitle-mapping}） */
    public static final String SOURCE_MAPPING = "mapping";

    /** 兼容旧写法的回退：素材目录下按 {@code {标识}.srt} 命中 */
    public static final String SOURCE_LEGACY = "legacy";
}
