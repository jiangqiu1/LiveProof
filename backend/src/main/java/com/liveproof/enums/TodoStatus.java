package com.liveproof.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 待办状态。{@code done} 与 completedAt 联动：置为 done 写时间，离开 done 清空。
 */
public enum TodoStatus implements ValueEnum {

    OPEN("open"),
    IN_PROGRESS("in_progress"),
    DONE("done"),
    CANCELLED("cancelled");

    @EnumValue
    private final String value;

    TodoStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static TodoStatus fromValue(String raw) {
        return EnumSupport.parse(TodoStatus.class, raw, "status");
    }
}
