package com.liveproof.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 字幕来源。
 *
 * <p>两个来源返回<b>同一种</b> cue 结构（index/startSec/endSec/text），
 * 来源只描述「这段文字是从哪来的」，<b>不描述</b>它的可信度：
 * 云端智能字幕同样是机器生成结果，仍须人工核对。
 *
 * <ul>
 *   <li>{@code local} —— 本地 SRT 文件解析（{@code GET /api/subtitles}）</li>
 *   <li>{@code cloud} —— 保利威智能字幕（{@code GET /api/smart-subtitles/tasks/{taskId}/cues}）</li>
 * </ul>
 *
 * <p>两者条数不一致（本地 12 条 / 云端 29 条），所以匹配一律按时间区间重叠，
 * 禁止按序号对应。
 */
public enum SubtitleSource implements ValueEnum {

    LOCAL("local"),
    CLOUD("cloud");

    private final String value;

    SubtitleSource(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static SubtitleSource fromValue(String raw) {
        return EnumSupport.parse(SubtitleSource.class, raw, "subtitleSource");
    }
}
