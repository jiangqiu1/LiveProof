package com.liveproof.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 流程进度：这条用例人工看过没有。
 *
 * <p>只描述流程，不得由 answer_status / evidence_* 推断。
 */
public enum ReviewStatus implements ValueEnum {

    UNREVIEWED("unreviewed"),
    IN_REVIEW("in_review"),
    REVIEWED("reviewed");

    @EnumValue
    private final String value;

    ReviewStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ReviewStatus fromValue(String raw) {
        return EnumSupport.parse(ReviewStatus.class, raw, "reviewStatus");
    }
}
