package com.liveproof.polyv;

import com.liveproof.config.PolyvProperties;
import com.liveproof.exception.PolyvErrors;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 保利威 v3/v4 签名器（阶段一已实测验证的规则）。
 *
 * <p>规则（官方《MD5/SHA256 签名生成规则》+ 阶段一实测）：
 * <ol>
 *   <li>取参数值非空的参数，按参数名字典序升序；</li>
 *   <li>连接参数名与参数值，形如 {@code k1v1k2v2}，<b>没有 {@code =} 也没有 {@code &}</b>；</li>
 *   <li>在拼接串首尾各加一次 secretKey；</li>
 *   <li>UTF-8 计算 MD5，转 32 位大写。</li>
 * </ol>
 *
 * <p>与点播 v2 的区别：v2 是 {@code k=v&k=v} 尾部接 key + SHA1 40 位大写，
 * v4 智能字幕<b>不是</b>那一套，混用会得到 10002 签名错误。
 *
 * <p>安全：本类不记录任何参数、拼接串或签名的明文。
 */
@Component
public class PolyvSigner {

    private final PolyvProperties props;

    public PolyvSigner(PolyvProperties props) {
        this.props = props;
    }

    /** 用配置中的 appSecret 签名。未配置时抛 503。 */
    public String sign(Map<String, ?> params) {
        if (!props.configured()) {
            throw PolyvErrors.notConfigured();
        }
        return sign(params, props.getAppSecret());
    }

    /**
     * 用显式 secret 签名（测试与多密钥场景）。
     *
     * @param params 参与签名的参数；值为 null / 空串的参数会被忽略
     * @param secret 密钥，禁止落盘、禁止打印
     */
    public String sign(Map<String, ?> params, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("签名密钥不能为空");
        }
        return md5HexUpper(plain(params, secret));
    }

    /** 拼接明文，仅供内部与测试比对，不对外暴露。 */
    static String plain(Map<String, ?> params, String secret) {
        TreeMap<String, String> sorted = new TreeMap<>();
        params.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            String text = String.valueOf(value);
            if (text.isEmpty()) {
                return;
            }
            sorted.put(key, text);
        });
        StringBuilder sb = new StringBuilder(secret);
        sorted.forEach((key, value) -> sb.append(key).append(value));
        return sb.append(secret).toString();
    }

    /**
     * 点播 v2 签名：{@code SHA1(k1=v1&k2=v2... + secretKey)} 转 40 位大写。
     *
     * <p>与 v3/v4 的 MD5 规则<b>不可混用</b>（混用会得到 {@code code=400 the sign is not right}）。
     * 用于 {@code get-video-info}、{@code snapshot/addTask}、{@code snapshot/getTaskStatus}。
     *
     * @param params 参与签名的参数（空值忽略）
     * @param secretKey 点播账号的 secretkey，禁止落盘与打印
     */
    public String signV2(Map<String, ?> params, String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("点播 secretkey 不能为空");
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        params.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            String text = String.valueOf(value);
            if (text.isEmpty()) {
                return;
            }
            sorted.put(key, text);
        });
        StringBuilder sb = new StringBuilder();
        sorted.forEach((key, value) -> {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(key).append('=').append(value);
        });
        return sha1HexUpper(sb + secretKey);
    }

    static String sha1HexUpper(String plain) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(plain.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 算法不可用", e);
        }
    }

    static String md5HexUpper(String plain) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(plain.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // JDK 必带 MD5，走到这里说明运行环境异常
            throw new IllegalStateException("MD5 算法不可用", e);
        }
    }
}
