package com.liveproof.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 证据充分性：证据够不够。
 *
 * <p>与 {@link EvidenceType} 是两个独立维度，禁止由形态推导充分性。
 */
public enum EvidenceSufficiency implements ValueEnum {

    NOT_CHECKED("not_checked"),
    INSUFFICIENT("insufficient"),
    SUFFICIENT("sufficient");

    @EnumValue
    private final String value;

    EvidenceSufficiency(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static EvidenceSufficiency fromValue(String raw) {
        return EnumSupport.parse(EvidenceSufficiency.class, raw, "evidenceSufficiency");
    }
}
