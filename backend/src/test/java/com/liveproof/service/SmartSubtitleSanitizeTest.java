package com.liveproof.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误信息脱敏测试。
 *
 * <p>红线：写入数据库/返回前端的错误信息里不得出现 sign 取值、appSecret 取值，
 * 也不得出现任何 32 位十六进制串（签名即该形态）。
 */
class SmartSubtitleSanitizeTest {

    @Test
    @DisplayName("sign 参数被抹掉")
    void stripsSignParameter() {
        String cleaned = SmartSubtitleService.sanitize(
                "调用保利威失败：sign=CBBF5A3B110973F0A5EF179C989A4356&appId=appid1234");

        assertThat(cleaned).doesNotContain("CBBF5A3B110973F0A5EF179C989A4356")
                .contains("sign=***");
    }

    @Test
    @DisplayName("appSecret / secretkey 取值被抹掉")
    void stripsSecretParameters() {
        assertThat(SmartSubtitleService.sanitize("appSecret=abcdef123&x=1"))
                .doesNotContain("abcdef123");
        assertThat(SmartSubtitleService.sanitize("secretkey=zzz999"))
                .doesNotContain("zzz999");
    }

    @Test
    @DisplayName("裸的 32 位十六进制串被抹掉")
    void stripsBareHash() {
        String cleaned = SmartSubtitleService.sanitize("上游返回 22E0D44B4F9796C989B76D46A1D09766 无法解析");

        assertThat(cleaned).doesNotContain("22E0D44B4F9796C989B76D46A1D09766").contains("***");
    }

    @Test
    @DisplayName("超长信息被截断到列宽以内，换行被压平")
    void truncatesLongMessage() {
        String cleaned = SmartSubtitleService.sanitize("错误\n" + "很长的说明".repeat(100));

        assertThat(cleaned.length()).isLessThanOrEqualTo(201);
        assertThat(cleaned).doesNotContain("\n");
    }

    @Test
    @DisplayName("null 原样返回，不制造空串")
    void handlesNull() {
        assertThat(SmartSubtitleService.sanitize(null)).isNull();
    }
}
