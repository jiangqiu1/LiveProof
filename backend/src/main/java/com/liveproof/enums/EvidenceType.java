package com.liveproof.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 证据形态：证据长什么样。
 *
 * <p>{@code unknown} 是合法值，表示形态待人工确认，绝不自动推断。
 */
public enum EvidenceType implements ValueEnum {

    VERBAL_CLAIM("verbal_claim"),
    SPECIFICATION_DISPLAY("specification_display"),
    OPERATIONAL_EVIDENCE("operational_evidence"),
    UNKNOWN("unknown");

    @EnumValue
    private final String value;

    EvidenceType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static EvidenceType fromValue(String raw) {
        return EnumSupport.parse(EvidenceType.class, raw, "evidenceType");
    }
}
