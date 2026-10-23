package com.liveproof.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 保利威智能字幕配置（liveproof.polyv.*）。
 *
 * <p>凭据来源沿用阶段一实测可用的既有键名：
 * {@code POLYV_LIVE_APP_ID} / {@code POLYV_LIVE_APP_SECRET}
 * （v4 智能字幕使用 appId + appSecret + MD5，而不是点播 v2 的 userid + secretkey + SHA1）。
 *
 * <p>安全约束：本类只持有密钥的内存副本。{@link #toString()} 已被重写为不含凭据，
 * 任何日志、响应、异常信息都不得拼接 appSecret 或 sign。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "liveproof.polyv")
public class PolyvProperties {

    private static final Logger log = LoggerFactory.getLogger(PolyvProperties.class);

    private static final Pattern VID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{4,64}$");

    /** 保利威 AppID（v4 签名身份）。为空表示未配置。 */
    private String appId = "";

    /** 保利威 AppSecret（v4 签名密钥）。为空表示未配置。 */
    private String appSecret = "";

    /** API 基地址，测试可指向本地桩服务。 */
    private String baseUrl = "https://api.polyv.net";

    /** 连接超时（毫秒）。 */
    private int connectTimeoutMs = 5000;

    /** 读超时（毫秒）。任务创建在实测中 369ms，留足余量但不无限等待。 */
    private int timeoutMs = 15000;

    /** 未显式指定语言时使用；必须按素材实际语言传，不能靠默认值碰运气。 */
    private String defaultLanguage = "chinese";

    /** 允许的语言取值（保利威按语言选择识别模型）。 */
    private List<String> allowedLanguages = List.of("chinese", "english", "japanese", "korean");

    /** 视频标识（媒体白名单文件名）-> VID。客户端不得自行指定 VID。 */
    private Map<String, String> videoVids = new LinkedHashMap<>();

    /** 视频标识 -> 时长（秒）。用于字幕时间越界校验。 */
    private Map<String, Double> videoDurations = new LinkedHashMap<>();

    /** 单次取回的字幕条数上限，防止异常响应把内存打满。 */
    private int maxCues = 5000;

    // ── 点播 v2（素材上传 / 视频信息 / 指定时间点截图）──
    // 与 v4 智能字幕是两套凭据：v2 用 userid + secretkey + SHA1，v4 用 appId + appSecret + MD5。

    /** 点播账号 userid（v2 签名身份，同时是 URL 路径段）。 */
    private String userId = "";

    /** 点播账号 secretkey（v2 签名密钥）。 */
    private String secretKey = "";

    /** 上传写密钥。为空表示未配置，上传接口返回 503。 */
    private String writeToken = "";

    /** 上传端点（官方文档给的是 v.polyv.net，不是 api.polyv.net）。 */
    private String uploadBaseUrl = "https://v.polyv.net";

    /** 上传读超时（毫秒）。200MB 慢网也要给足。 */
    private int uploadTimeoutMs = 600000;

    /** 截图轮询总超时（毫秒）。实测 8s 视频约 2s 出结果，留足余量。 */
    private int snapshotTimeoutMs = 30000;

    /** 截图轮询间隔（毫秒）。 */
    private int snapshotPollIntervalMs = 1000;

    /** 期望的截图宽度；实际上限受源视频宽度约束（超过会返回 "width over limit"）。 */
    private int snapshotWidth = 720;

    /** 单次截图任务最多时间点数（官方上限 20）。 */
    private int snapshotMaxTimes = 20;

    public boolean vodConfigured() {
        return userId != null && !userId.isBlank() && secretKey != null && !secretKey.isBlank();
    }

    public boolean uploadConfigured() {
        return vodConfigured() && writeToken != null && !writeToken.isBlank();
    }

    public boolean configured() {
        return appId != null && !appId.isBlank() && appSecret != null && !appSecret.isBlank();
    }

    public Optional<String> vidOf(String video) {
        if (video == null) {
            return Optional.empty();
        }
        String vid = videoVids.get(video);
        return vid == null || vid.isBlank() ? Optional.empty() : Optional.of(vid.trim());
    }

    public Optional<Double> durationOf(String video) {
        if (video == null) {
            return Optional.empty();
        }
        Double duration = videoDurations.get(video);
        return duration == null || duration <= 0 ? Optional.empty() : Optional.of(duration);
    }

    public boolean languageAllowed(String language) {
        return language != null && allowedLanguages.contains(language);
    }

    /** 主机名（不含端口以外的路径），用于状态接口展示，不泄露凭据。 */
    public String host() {
        try {
            URI uri = URI.create(baseUrl);
            return uri.getHost() == null ? baseUrl : uri.getHost();
        } catch (IllegalArgumentException e) {
            return baseUrl;
        }
    }

    @PostConstruct
    public void init() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("配置错误：liveproof.polyv.base-url 不能为空");
        }
        try {
            URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("配置错误：liveproof.polyv.base-url 不是合法 URL");
        }
        videoVids.forEach((video, vid) -> {
            if (video == null || video.isBlank()) {
                throw new IllegalStateException("配置错误：liveproof.polyv.video-vids 含空视频标识");
            }
            // 未填 VID 时占位为空串：视为「该视频未接入」，接口返回 400 POLYV_VID_NOT_CONFIGURED，
            // 而不是让整个服务启动失败（本地 SRT 链路与其余功能不受影响）。
            if (vid == null || vid.isBlank()) {
                log.warn("保利威 VID 未配置：视频 {}（该视频的智能字幕接口不可用）", video);
                return;
            }
            if (!VID_PATTERN.matcher(vid.trim()).matches()) {
                throw new IllegalStateException(
                        "配置错误：liveproof.polyv.video-vids 的 VID 格式非法（视频 " + video + "）");
            }
        });
        videoDurations.forEach((video, duration) -> {
            if (duration == null || duration <= 0) {
                throw new IllegalStateException(
                        "配置错误：liveproof.polyv.video-durations 时长必须为正数（视频 " + video + "）");
            }
        });

        if (configured()) {
            log.info("保利威智能字幕已配置：host={} 视频映射 {} 个（凭据不打印）",
                    host(), videoVids.size());
        } else {
            log.warn("保利威智能字幕未配置：缺少 POLYV_LIVE_APP_ID / POLYV_LIVE_APP_SECRET，"
                    + "相关接口将返回 503 POLYV_NOT_CONFIGURED");
        }

        if (vodConfigured()) {
            log.info("保利威点播 v2 已配置：userid 长度 {}，上传{}",
                    userId.length(), uploadConfigured() ? "可用" : "不可用（缺少 POLYV_VOD_WRITE_TOKEN）");
        } else {
            log.warn("保利威点播 v2 未配置：缺少 POLYV_VOD_USER_ID / POLYV_VOD_SECRET_KEY，"
                    + "素材上传、云端播放地址与云端截图不可用");
        }
        if (snapshotWidth <= 0 || snapshotWidth > 4000) {
            throw new IllegalStateException("配置错误：liveproof.polyv.snapshot-width 必须在 1–4000 之间");
        }
        if (snapshotMaxTimes <= 0 || snapshotMaxTimes > 20) {
            throw new IllegalStateException("配置错误：liveproof.polyv.snapshot-max-times 必须在 1–20 之间");
        }
    }

    /** 不含任何凭据。 */
    @Override
    public String toString() {
        return "PolyvProperties{baseUrl=" + baseUrl + ", configured=" + configured()
                + ", vodConfigured=" + vodConfigured() + ", uploadConfigured=" + uploadConfigured()
                + ", videos=" + videoVids.size() + "}";
    }
}
