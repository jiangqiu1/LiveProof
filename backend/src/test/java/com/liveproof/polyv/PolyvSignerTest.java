package com.liveproof.polyv;

import com.liveproof.config.PolyvProperties;
import com.liveproof.exception.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 签名规则测试。
 *
 * <p>参考值由独立实现（Python hashlib）按官方文档规则算出，用于确认 Java 实现与文档一致：
 * {@code MD5(secret + Σ(k+v 按 k 升序) + secret)}，32 位大写。
 * 这是全项目唯一允许"硬编码期望值"的地方——它是对外部协议的契约，不是对自身实现的复述。
 */
class PolyvSignerTest {

    private static final String SECRET = "Vk9secretVk9";

    private PolyvSigner signer(String appId, String appSecret) {
        PolyvProperties props = new PolyvProperties();
        props.setAppId(appId);
        props.setAppSecret(appSecret);
        return new PolyvSigner(props);
    }

    @Test
    @DisplayName("创建接口 URL 参数（appId + timestamp）签名与独立实现一致")
    void signsCreateParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("appId", "appid1234");
        params.put("timestamp", 1700000000000L);

        assertThat(signer("appid1234", SECRET).sign(params, SECRET))
                .isEqualTo("CBBF5A3B110973F0A5EF179C989A4356");
    }

    @Test
    @DisplayName("查询接口多带 taskId 时签名与独立实现一致")
    void signsGetParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("appId", "appid1234");
        params.put("timestamp", 1700000000000L);
        params.put("taskId", 522143L);

        assertThat(signer("appid1234", SECRET).sign(params, SECRET))
                .isEqualTo("22E0D44B4F9796C989B76D46A1D09766");
    }

    @Test
    @DisplayName("参数顺序不影响结果，空值与 null 不参与签名")
    void ignoresOrderAndBlankValues() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("appId", "a");
        ordered.put("timestamp", 1L);

        Map<String, Object> shuffled = new LinkedHashMap<>();
        shuffled.put("timestamp", 1L);
        shuffled.put("appId", "a");
        shuffled.put("sign", "");
        shuffled.put("empty", null);

        assertThat(signer("a", "s").sign(shuffled, "s"))
                .isEqualTo(signer("a", "s").sign(ordered, "s"))
                .isEqualTo("762786876CE95E373D5EC51A4FFF59FE");
    }

    @Test
    @DisplayName("结果固定 32 位大写十六进制（不是 v2 的 SHA1 40 位）")
    void producesUppercaseMd5() {
        Map<String, Object> params = Map.of("appId", "a", "timestamp", 1L);
        String sign = signer("a", "s").sign(params, "s");

        assertThat(sign).hasSize(32).matches("^[0-9A-F]{32}$");
    }

    @Test
    @DisplayName("secret 首尾各参与一次，少一次就与期望不符")
    void couplesSecretOnBothSides() {
        Map<String, Object> params = Map.of("appId", "a");
        String withSecretTwice = PolyvSigner.md5HexUpper("s" + "appIda" + "s");

        assertThat(signer("a", "s").sign(params, "s")).isEqualTo(withSecretTwice);
    }

    @Test
    @DisplayName("未配置凭据时签名抛 503，不返回任何签名值")
    void rejectsMissingSecret() {
        PolyvSigner blank = signer("", "");

        assertThatThrownBy(() -> blank.sign(Map.of("appId", "a")))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo("POLYV_NOT_CONFIGURED");
    }

    @Test
    @DisplayName("空 secret 直接拒绝，不做空密钥签名")
    void rejectsBlankSecretArgument() {
        assertThatThrownBy(() -> signer("a", "").sign(Map.of("appId", "a"), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
