package com.liveproof.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 待办来源：人工新建 / 由历史 follow_up_required 迁移派生。保留追溯能力。
 */
public enum TodoSource implements ValueEnum {

    MANUAL("manual"),
    DERIVED_FROM_CASE("derived_from_case");

    @EnumValue
    private final String value;

    TodoSource(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static TodoSource fromValue(String raw) {
        return EnumSupport.parse(TodoSource.class, raw, "source");
    }
}
