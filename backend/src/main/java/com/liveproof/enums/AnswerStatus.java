package com.liveproof.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 回答状态：视频里有没有这样说。
 *
 * <p>只回答"说了没有"，不等于"内容为真"。
 */
public enum AnswerStatus implements ValueEnum {

    ANSWERED("answered"),
    PARTIALLY_ANSWERED("partially_answered"),
    NOT_FOUND("not_found");

    @EnumValue
    private final String value;

    AnswerStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static AnswerStatus fromValue(String raw) {
        return EnumSupport.parse(AnswerStatus.class, raw, "answerStatus");
    }
}
