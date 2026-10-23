package com.liveproof.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 能力配置（liveproof.ai.*）。
 *
 * <p>密钥来源：环境变量 {@code DEEPSEEK_API_KEY}（或本地 .env.local 同名键）。
 * 本类只持有密钥的<b>内存副本</b>，不写日志、不进响应、不进前端产物。
 *
 * <p>未配置密钥时 {@link #configured()} 为 false，调用方必须返回明确的「未配置」错误，
 * <b>不允许</b>回退到示例答案或随机结果。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "liveproof.ai")
public class AiProperties {

    private static final Logger log = LoggerFactory.getLogger(AiProperties.class);

    /** 模型服务 API Key；为空表示运行时未配置。 */
    private String apiKey = "";

    /** OpenAI 兼容的 base_url，官方默认 https://api.deepseek.com。 */
    private String baseUrl = "https://api.deepseek.com";

    /** 模型名；官方文档当前支持图像输入的模型为 deepseek-flash。 */
    private String model = "deepseek-flash";

    /** 单次请求读超时（毫秒）。 */
    private int timeoutMs = 60000;

    /** 连接超时（毫秒）。 */
    private int connectTimeoutMs = 8000;

    /** 网络异常 / 5xx / 429 的最大重试次数（不含首次请求）。 */
    private int maxRetries = 1;

    /** 重试退避基数（毫秒）。 */
    private int retryBackoffMs = 800;

    /** 模型输出上限。 */
    private int maxTokens = 2048;

    private double temperature = 0.2;

    /** 是否允许把关键帧作为图像输入提交给模型。 */
    private boolean visionEnabled = true;

    /** 单次分析最多提取/提交的关键帧数量。 */
    private int maxFrames = 4;

    /** 关键帧最大宽度（像素），高度按比例缩放。 */
    private int frameMaxWidth = 720;

    /** 单帧 JPEG 字节上限，超过视为提取失败。 */
    private int frameMaxBytes = 3 * 1024 * 1024;

    /** 单次请求提交的图片总字节上限。 */
    private int requestMaxImageBytes = 8 * 1024 * 1024;

    /** 提交给模型的字幕正文最大字符数。 */
    private int maxSubtitleChars = 6000;

    /** 时间窗之外额外检索的字幕条数上限。 */
    private int maxExtraCues = 3;

    /** 关键帧提取进程超时（毫秒）。 */
    private int ffmpegTimeoutMs = 20000;

    /** FFmpeg 可执行文件路径；未在 PATH 中时用环境变量指定绝对路径。 */
    private String ffmpegPath = "ffmpeg";

    /** 报告 AI 归纳部分的最大字符数（超出截断）。 */
    private int maxNarrativeChars = 2000;

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @PostConstruct
    public void init() {
        if (!configured()) {
            log.warn("AI 能力未配置：DEEPSEEK_API_KEY 为空，AI 辅助核验与 AI 归纳将返回明确的未配置错误");
            return;
        }
        log.info("AI 能力已配置：model={} baseUrl={} vision={} maxFrames={}",
                model, baseUrl, visionEnabled, maxFrames);
    }
}
