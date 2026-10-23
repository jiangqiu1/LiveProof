package com.liveproof.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liveproof.config.AiProperties;
import com.liveproof.exception.AiErrors;
import com.liveproof.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek（OpenAI 兼容）对话补全客户端。
 *
 * <p>安全约束：
 * <ul>
 *   <li>密钥只在 Authorization 头中出现，绝不写入日志、响应或异常信息</li>
 *   <li>不记录完整 prompt（其中含字幕与用例内容），只记录字符数与耗时</li>
 *   <li>图片以 base64 data URL 内联，不产生临时文件，也不向第三方暴露本地路径</li>
 * </ul>
 *
 * <p>重试策略：仅对 429 / 5xx / 超时 / 网络异常重试，次数由配置控制，避免放大成本。
 */
@Component
public class DeepSeekClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private final AiProperties props;
    private final ObjectMapper mapper;
    private final RestClient restClient;
    private final String endpointHost;

    public DeepSeekClient(AiProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(props.getTimeoutMs()));
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.endpointHost = hostOf(props.getBaseUrl());
    }

    @Override
    public boolean isConfigured() {
        return props.configured();
    }

    @Override
    public String model() {
        return props.getModel();
    }

    @Override
    public String endpointHost() {
        return endpointHost;
    }

    @Override
    public AiCompletion complete(String systemPrompt, String userPrompt,
                                 List<FrameImage> frames, boolean jsonMode) {
        if (!props.configured()) {
            throw AiErrors.notConfigured();
        }
        List<FrameImage> safeFrames = frames == null ? List.of() : frames;
        Map<String, Object> body = buildBody(systemPrompt, userPrompt, safeFrames, jsonMode);

        int attempts = Math.max(0, props.getMaxRetries()) + 1;
        long started = System.currentTimeMillis();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String raw = post(body);
                AiCompletion completion = parseCompletion(raw);
                log.info("AI 调用完成 model={} promptChars={} frames={} elapsed={}ms tokens={}/{}",
                        completion.model(), userPrompt.length(), safeFrames.size(),
                        System.currentTimeMillis() - started,
                        completion.promptTokens(), completion.completionTokens());
                return completion;
            } catch (RetryableFailure e) {
                last = e.error;
                log.warn("AI 调用第 {}/{} 次失败：{}", attempt, attempts, e.reason);
                if (attempt == attempts) {
                    break;
                }
                sleep(props.getRetryBackoffMs() * attempt);
            }
        }
        throw last == null ? AiErrors.upstream("未知原因") : last;
    }

    private Map<String, Object> buildBody(String systemPrompt, String userPrompt,
                                          List<FrameImage> frames, boolean jsonMode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("temperature", props.getTemperature());
        body.put("max_tokens", props.getMaxTokens());
        body.put("stream", false);
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        // 图片只允许出现在 user 消息中；system 保持纯文本
        Object userContent = frames.isEmpty() ? userPrompt : multimodal(userPrompt, frames);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent)));
        return body;
    }

    private static Object multimodal(String userPrompt, List<FrameImage> frames) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(Map.of("type", "text", "text", userPrompt));
        for (FrameImage frame : frames) {
            blocks.add(Map.of("type", "text", "text", "【关键帧 " + fmt(frame.sec()) + "】"));
            blocks.add(Map.of("type", "image_url", "image_url",
                    Map.of("url", frame.dataUrl(), "detail", "high")));
        }
        return blocks;
    }

    private String post(Map<String, Object> body) {
        try {
            return restClient.post()
                    .uri(chatCompletionsUri())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw AiErrors.authFailed();
            }
            String snippet = snippet(e.getResponseBodyAsString());
            if (status == 429) {
                throw new RetryableFailure("HTTP 429（限流）", AiErrors.rateLimited());
            }
            if (status >= 500) {
                throw new RetryableFailure("HTTP " + status, AiErrors.upstream("服务端返回 " + status));
            }
            throw AiErrors.upstream("请求被拒绝（HTTP " + status + (snippet.isBlank() ? "" : "：" + snippet) + "）");
        } catch (ResourceAccessException e) {
            String message = String.valueOf(e.getMessage()).toLowerCase();
            boolean timeout = message.contains("timed out") || message.contains("timeout");
            ApiException error = timeout ? AiErrors.timeout() : AiErrors.upstream("网络异常，无法连接模型服务");
            throw new RetryableFailure(timeout ? "读超时" : "网络异常", error);
        }
    }

    private AiCompletion parseCompletion(String raw) {
        if (raw == null || raw.isBlank()) {
            throw AiErrors.invalidResponse("上游返回空响应");
        }
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (Exception e) {
            throw AiErrors.invalidResponse("上游响应不是合法 JSON");
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw AiErrors.invalidResponse("上游响应缺少 choices");
        }
        JsonNode message = choices.get(0).path("message");
        String content = message.path("content").isMissingNode() || message.path("content").isNull()
                ? null : message.path("content").asText(null);
        if (content == null || content.isBlank()) {
            String finish = choices.get(0).path("finish_reason").asText("");
            throw AiErrors.invalidResponse(
                    "模型未返回文本内容" + (finish.isBlank() ? "" : "（finish_reason=" + finish + "）"));
        }
        JsonNode usage = root.path("usage");
        return new AiCompletion(
                content,
                root.path("model").asText(props.getModel()),
                usage.hasNonNull("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null,
                usage.hasNonNull("completion_tokens") ? usage.get("completion_tokens").asInt() : null);
    }

    private URI chatCompletionsUri() {
        String base = props.getBaseUrl() == null ? "" : props.getBaseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            return URI.create(base);
        }
        return URI.create(base + "/chat/completions");
    }

    private static String hostOf(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return host == null ? "(invalid)" : host;
        } catch (Exception e) {
            return "(invalid)";
        }
    }

    /** 上游错误正文只保留很短的片段，且过滤掉可能出现的凭据样式字符串。 */
    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        flat = flat.replaceAll("(?i)bearer\\s+\\S+", "bearer ***");
        flat = flat.replaceAll("sk-[A-Za-z0-9_-]{6,}", "sk-***");
        return flat.length() > 160 ? flat.substring(0, 160) + "…" : flat;
    }

    static String fmt(double sec) {
        int total = (int) Math.round(sec);
        return String.format("%02d:%02d", total / 60, total % 60);
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 可重试失败的内部载体：保留对外的 ApiException，同时带上日志用原因。 */
    private static final class RetryableFailure extends RuntimeException {
        private final ApiException error;
        private final String reason;

        RetryableFailure(String reason, ApiException error) {
            super(reason, null, false, false);
            this.reason = reason;
            this.error = error;
        }
    }
}
