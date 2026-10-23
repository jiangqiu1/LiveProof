package com.liveproof.polyv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liveproof.config.PolyvProperties;
import com.liveproof.exception.PolyvErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 保利威智能字幕（vod v4）客户端：创建任务、查询任务。
 *
 * <p>已实测的平台行为（阶段一记录）：
 * <ul>
 *   <li>创建：{@code POST /vod/v4/smart-subtitle/create}，body 里的 {@code vid}/{@code language}
 *       不参与签名，签名只覆盖 URL 参数（appId + timestamp）</li>
 *   <li>查询：{@code GET /vod/v4/smart-subtitle/get}，签名覆盖 appId + timestamp + taskId</li>
 *   <li>错误以 HTTP 4xx + JSON {@code error.code} 返回，因此<b>必须解析 body 而不是只看状态码</b></li>
 * </ul>
 *
 * <p>安全约束：
 * <ul>
 *   <li>appSecret 只用于本地计算签名，不进入 URL、不进入 body、不写日志</li>
 *   <li>日志只记 taskId、耗时、条目数与错误码</li>
 *   <li>抛出的异常 message 不含签名与请求体</li>
 * </ul>
 */
@Component
public class PolyvSubtitleClient {

    private static final Logger log = LoggerFactory.getLogger(PolyvSubtitleClient.class);

    static final String PATH_CREATE = "/vod/v4/smart-subtitle/create";
    static final String PATH_GET = "/vod/v4/smart-subtitle/get";

    private final PolyvProperties props;
    private final PolyvSigner signer;
    private final ObjectMapper mapper;
    private final RestClient restClient;

    public PolyvSubtitleClient(PolyvProperties props, PolyvSigner signer, ObjectMapper mapper) {
        this.props = props;
        this.signer = signer;
        this.mapper = mapper;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(props.getTimeoutMs()));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return props.configured();
    }

    /**
     * 创建一个智能字幕任务。
     *
     * <p><b>注意</b>：本方法会真实消耗账号资源点，调用方必须先查本地任务表复用既有任务。
     *
     * @return 平台 taskId
     */
    public long createTask(String vid, String language) {
        if (!props.configured()) {
            throw PolyvErrors.notConfigured();
        }
        if (vid == null || vid.isBlank()) {
            throw PolyvErrors.vidNotConfigured("(空值)");
        }
        if (!props.languageAllowed(language)) {
            throw new IllegalArgumentException("不支持的字幕语言：" + language);
        }

        long timestamp = System.currentTimeMillis();
        Map<String, Object> signed = new LinkedHashMap<>();
        signed.put("appId", props.getAppId());
        signed.put("timestamp", timestamp);
        String url = props.getBaseUrl() + PATH_CREATE + "?appId=" + props.getAppId()
                + "&timestamp=" + timestamp
                + "&sign=" + signer.sign(signed);

        // autoApplyEnabled=N：不自动替换视频字幕；repetitionEnabled=N：不做智能断句重复处理（阶段一实测配置）
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vid", vid);
        body.put("language", language);
        body.put("autoApplyEnabled", "N");
        body.put("repetitionEnabled", "N");

        long started = System.currentTimeMillis();
        JsonNode root = exchange(url, body);
        long elapsed = System.currentTimeMillis() - started;

        ensureSuccess(root);
        JsonNode taskIdNode = root.path("data").path("taskId");
        if (taskIdNode.isMissingNode() || taskIdNode.isNull()) {
            throw PolyvErrors.upstream("创建接口未返回 taskId");
        }
        long taskId;
        try {
            taskId = taskIdNode.isNumber() ? taskIdNode.asLong() : Long.parseLong(taskIdNode.asText().trim());
        } catch (NumberFormatException e) {
            throw PolyvErrors.upstream("创建接口返回的 taskId 不是数字");
        }
        log.info("智能字幕任务已创建 vid={} taskId={} language={} elapsed={}ms",
                vid, taskId, language, elapsed);
        return taskId;
    }

    /**
     * 查询任务。56810 抛 404，56812 返回 {@link PolyvTaskState#PROCESSING}。
     *
     * <p>只读操作，不消耗资源点，可安全重复调用。
     */
    public PolyvSubtitleSnapshot fetchTask(String taskId) {
        if (!props.configured()) {
            throw PolyvErrors.notConfigured();
        }
        long numericTaskId;
        try {
            numericTaskId = Long.parseLong(taskId == null ? "" : taskId.trim());
        } catch (NumberFormatException e) {
            throw PolyvErrors.localTaskNotFound(String.valueOf(taskId));
        }

        long timestamp = System.currentTimeMillis();
        Map<String, Object> signed = new LinkedHashMap<>();
        signed.put("appId", props.getAppId());
        signed.put("timestamp", timestamp);
        signed.put("taskId", numericTaskId);
        String url = props.getBaseUrl() + PATH_GET + "?appId=" + props.getAppId()
                + "&timestamp=" + timestamp
                + "&taskId=" + numericTaskId
                + "&sign=" + signer.sign(signed);

        long started = System.currentTimeMillis();
        JsonNode root = exchange(url, null);
        long elapsed = System.currentTimeMillis() - started;

        int errorCode = root.path("error").path("code").asInt(0);
        if (errorCode == 56812) {
            log.info("智能字幕仍在生成中 taskId={} elapsed={}ms", numericTaskId, elapsed);
            return new PolyvSubtitleSnapshot(numericTaskId, PolyvTaskState.PROCESSING, null, null, List.of());
        }
        if (errorCode == 56810) {
            throw PolyvErrors.taskNotFoundInPlatform();
        }
        ensureSuccess(root);

        JsonNode data = root.path("data");
        JsonNode subtitles = data.path("subtitles");
        if (!subtitles.isArray()) {
            throw PolyvErrors.upstream("查询接口未返回 subtitles 数组");
        }
        if (subtitles.size() > props.getMaxCues()) {
            throw PolyvErrors.invalidSubtitles(
                    "条数 " + subtitles.size() + " 超过上限 " + props.getMaxCues());
        }
        List<PolyvSubtitleSnapshot.PolyvCue> cues = new ArrayList<>(subtitles.size());
        for (int i = 0; i < subtitles.size(); i++) {
            JsonNode item = subtitles.get(i);
            JsonNode startNode = item.path("start");
            JsonNode endNode = item.path("end");
            if (!startNode.isNumber() || !endNode.isNumber()) {
                throw PolyvErrors.invalidSubtitles("第 " + (i + 1) + " 条缺少毫秒时间字段");
            }
            cues.add(new PolyvSubtitleSnapshot.PolyvCue(
                    startNode.asLong(),
                    endNode.asLong(),
                    item.path("content").asText("")));
        }
        log.info("智能字幕取回成功 taskId={} cues={} elapsed={}ms", numericTaskId, cues.size(), elapsed);
        return new PolyvSubtitleSnapshot(
                numericTaskId,
                PolyvTaskState.READY,
                textOrNull(data.path("subtitleName")),
                textOrNull(data.path("link")),
                cues);
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    /** 平台业务成功判定：code == 200。 */
    private void ensureSuccess(JsonNode root) {
        int code = root.path("code").asInt(-1);
        if (code == 200) {
            return;
        }
        int errorCode = root.path("error").path("code").asInt(0);
        String desc = truncate(root.path("error").path("desc").asText(""), 120);
        switch (errorCode) {
            case 10002, 10003, 20003 -> throw PolyvErrors.authFailed("error=" + errorCode);
            case 56809 -> throw PolyvErrors.quotaExhausted(desc);
            case 56800 -> throw PolyvErrors.invalidVid(desc);
            case 56810 -> throw PolyvErrors.taskNotFoundInPlatform();
            default -> throw PolyvErrors.upstream(
                    "code=" + code + (errorCode == 0 ? "" : " error=" + errorCode)
                            + (desc.isBlank() ? "" : " desc=" + desc));
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }

    /** body 为 null 时发 GET，否则发 POST JSON。统一返回响应体（可能是错误 JSON）。 */
    private JsonNode exchange(String url, Map<String, Object> body) {
        String raw;
        try {
            RestClient.RequestBodySpec spec = restClient.method(body == null
                            ? org.springframework.http.HttpMethod.GET
                            : org.springframework.http.HttpMethod.POST)
                    .uri(URI.create(url));
            if (body == null) {
                raw = spec.retrieve().body(String.class);
            } else {
                raw = spec.contentType(MediaType.APPLICATION_JSON)
                        .body(mapper.writeValueAsString(body))
                        .retrieve()
                        .body(String.class);
            }
        } catch (RestClientResponseException e) {
            // 平台以 4xx + JSON body 报错，body 里才有 error.code
            raw = e.getResponseBodyAsString();
            if (raw == null || raw.isBlank()) {
                throw PolyvErrors.upstream("HTTP " + e.getStatusCode().value() + "，响应体为空");
            }
        } catch (ResourceAccessException e) {
            throw isTimeout(e) ? PolyvErrors.timeout() : PolyvErrors.upstream("网络异常");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw PolyvErrors.upstream("请求序列化失败");
        }

        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw PolyvErrors.upstream("响应不是合法 JSON");
        }
    }

    private static boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.net.http.HttpTimeoutException
                    || current instanceof java.net.SocketTimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("timed out")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
