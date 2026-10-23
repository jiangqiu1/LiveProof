package com.liveproof.enums;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 枚举取值解析工具。
 *
 * <p>设计约束：解析失败必须抛出异常，<b>不做兜底默认值</b>。
 * 四类核验字段禁止任何"猜一个合法值"的行为，否则会静默覆盖人工判断。
 */
public final class EnumSupport {

    private EnumSupport() {
    }

    /**
     * 按小写蛇形取值或枚举名解析（大小写不敏感）。
     * null 返回 null（表示未提供）；空串与非法取值一律抛 IllegalArgumentException。
     */
    public static <E extends Enum<E> & ValueEnum> E parse(Class<E> type, String raw, String fieldName) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim();
        for (E constant : type.getEnumConstants()) {
            if (key.equalsIgnoreCase(constant.getValue()) || key.equalsIgnoreCase(constant.name())) {
                return constant;
            }
        }
        String allowed = Arrays.stream(type.getEnumConstants())
                .map(ValueEnum::getValue)
                .collect(Collectors.joining(", "));
        throw new IllegalArgumentException(
                "非法取值 " + fieldName + "=" + raw + "，允许值：" + allowed);
    }
}
