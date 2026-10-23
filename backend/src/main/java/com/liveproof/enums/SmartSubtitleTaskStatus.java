package com.liveproof.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 智能字幕任务状态（本地登记口径，不是保利威的原始字段）。
 *
 * <p>语义边界：
 * <ul>
 *   <li>{@code created} —— 本地已发起创建，尚无任何云端查询结果</li>
 *   <li>{@code processing} —— 云端明确返回「生成中」（56812）</li>
 *   <li>{@code ready} —— 云端返回了字幕正文，且通过本地严格校验</li>
 *   <li>{@code failed} —— 云端报错，或字幕正文未通过校验（见 errorCode）</li>
 *   <li>{@code not_found} —— 云端返回任务不存在（56810）</li>
 * </ul>
 *
 * <p>{@code ready} 只代表「拿得到字幕」，不代表字幕内容正确。
 */
public enum SmartSubtitleTaskStatus implements ValueEnum {

    CREATED("created"),
    PROCESSING("processing"),
    READY("ready"),
    FAILED("failed"),
    NOT_FOUND("not_found");

    @EnumValue
    private final String value;

    SmartSubtitleTaskStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static SmartSubtitleTaskStatus fromValue(String raw) {
        return EnumSupport.parse(SmartSubtitleTaskStatus.class, raw, "status");
    }

    /** 可复用的状态：已发起且未被判定为失败/丢失。 */
    public boolean reusable() {
        return this == CREATED || this == PROCESSING || this == READY;
    }
}
