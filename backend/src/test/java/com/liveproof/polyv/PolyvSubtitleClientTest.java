package com.liveproof.polyv;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liveproof.config.PolyvProperties;
import com.liveproof.exception.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 保利威客户端测试。
 *
 * <p>用 JDK 自带 HttpServer 起一个本地桩服务，走<b>真实 HTTP</b>：
 * 断言的是我们自己的 URL 拼装、签名参数、报文解析与错误码映射。
 * 桩只替换"云端返回什么"，不替换传输层与解析逻辑。
 */
class PolyvSubtitleClientTest {

    private static final String APP_ID = "appid1234";
    private static final String APP_SECRET = "Vk9secretVk9";

    private static HttpServer server;
    private static String baseUrl;

    private static volatile String lastPath;
    private static volatile String lastQuery;
    private static volatile String lastBody;
    private static volatile int stubStatus;
    private static volatile String stubBody;

    private PolyvSubtitleClient client;
    private PolyvProperties props;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/vod/v4/smart-subtitle/", exchange -> {
            lastPath = exchange.getRequestURI().getPath();
            lastQuery = exchange.getRequestURI().getRawQuery();
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] payload = stubBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(stubStatus, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void setUp() {
        stubStatus = 200;
        stubBody = "{\"code\":200}";
        lastPath = null;
        lastQuery = null;
        lastBody = null;

        props = new PolyvProperties();
        props.setAppId(APP_ID);
        props.setAppSecret(APP_SECRET);
        props.setBaseUrl(baseUrl);
        props.setConnectTimeoutMs(2000);
        props.setTimeoutMs(3000);
        props.setMaxCues(3);
        client = new PolyvSubtitleClient(props, new PolyvSigner(props), new ObjectMapper());
    }

    @AfterEach
    void clearStub() {
        stubStatus = 200;
        stubBody = "{\"code\":200}";
    }

    // ---------------------------------------------------------------- 工具

    private static Map<String, String> queryParams(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            params.put(URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    /** 按官方规则独立重算签名，校验我们送出去的那一份是对的。 */
    private static String expectedSign(Map<String, String> withoutSign) {
        StringBuilder plain = new StringBuilder(APP_SECRET);
        withoutSign.keySet().stream().sorted()
                .forEach(key -> plain.append(key).append(withoutSign.get(key)));
        plain.append(APP_SECRET);
        return PolyvSigner.md5HexUpper(plain.toString());
    }

    private static String code(ApiException error) {
        return error.getCode();
    }

    // ---------------------------------------------------------------- 创建

    @Test
    @DisplayName("创建任务：URL 只带 appId+timestamp，签名正确，VID 在 body 且不参与签名")
    void createsTask() {
        stubBody = "{\"code\":200,\"status\":\"success\",\"data\":{\"taskId\":522143}}";

        long taskId = client.createTask("9df0_testvid", "chinese");

        assertThat(taskId).isEqualTo(522143L);
        assertThat(lastPath).isEqualTo("/vod/v4/smart-subtitle/create");

        Map<String, String> params = queryParams(lastQuery);
        assertThat(params).containsOnlyKeys("appId", "timestamp", "sign");
        assertThat(params.get("appId")).isEqualTo(APP_ID);
        assertThat(params.get("timestamp")).hasSize(13).matches("^[0-9]{13}$");

        Map<String, String> withoutSign = new LinkedHashMap<>(params);
        withoutSign.remove("sign");
        assertThat(params.get("sign")).isEqualTo(expectedSign(withoutSign));

        // 密钥绝不出现在 URL 或 body 里
        assertThat(lastQuery).doesNotContain(APP_SECRET);
        assertThat(lastBody).doesNotContain(APP_SECRET);

        assertThat(lastBody)
                .contains("\"vid\":\"9df0_testvid\"")
                .contains("\"language\":\"chinese\"")
                .contains("\"autoApplyEnabled\":\"N\"")
                .contains("\"repetitionEnabled\":\"N\"");
    }

    @Test
    @DisplayName("创建任务：taskId 为字符串时也能解析")
    void parsesStringTaskId() {
        stubBody = "{\"code\":200,\"data\":{\"taskId\":\"522143\"}}";
        assertThat(client.createTask("9df0_testvid", "chinese")).isEqualTo(522143L);
    }

    @Test
    @DisplayName("创建任务：资源点不足 56809 -> 503 POLYV_QUOTA_EXHAUSTED")
    void mapsQuotaExhausted() {
        stubStatus = 400;
        stubBody = "{\"code\":400,\"error\":{\"code\":56809,\"desc\":\"资源点不足\"}}";

        assertThatThrownBy(() -> client.createTask("9df0_testvid", "chinese"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(code((ApiException) e)).isEqualTo("POLYV_QUOTA_EXHAUSTED"));
    }

    @Test
    @DisplayName("创建任务：签名错 10002 -> 502 POLYV_AUTH_FAILED，且异常信息不含密钥")
    void mapsAuthFailure() {
        stubStatus = 403;
        stubBody = "{\"code\":403,\"error\":{\"code\":10002,\"desc\":\"签名错误\"}}";

        assertThatThrownBy(() -> client.createTask("9df0_testvid", "chinese"))
                .isInstanceOf(ApiException.class)
                .hasMessageNotContaining(APP_SECRET)
                .satisfies(e -> assertThat(code((ApiException) e)).isEqualTo("POLYV_AUTH_FAILED"));
    }

    @Test
    @DisplayName("创建任务：未配置凭据直接 503，且不发请求")
    void refusesWhenNotConfigured() {
        props.setAppId("");
        props.setAppSecret("");
        PolyvSubtitleClient offline = new PolyvSubtitleClient(props, new PolyvSigner(props), new ObjectMapper());

        assertThatThrownBy(() -> offline.createTask("9df0_testvid", "chinese"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(code((ApiException) e)).isEqualTo("POLYV_NOT_CONFIGURED"));
        assertThat(lastQuery).isNull();
    }

    @Test
    @DisplayName("创建任务：不支持的语言直接拒绝，不猜测")
    void rejectsUnsupportedLanguage() {
        assertThatThrownBy(() -> client.createTask("9df0_testvid", "klingon"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- 查询

    @Test
    @DisplayName("查询任务：READY 时原样保留毫秒时间与文本")
    void fetchesReadyTask() {
        stubBody = """
                {"code":200,"data":{"taskId":522143,"subtitleName":"智能字幕",
                 "link":"https://img.videocc.net/x.srt",
                 "subtitles":[
                   {"start":680,"end":1240,"content":"大家好"},
                   {"start":1480,"end":4800,"content":"这里是产品演示"}]}}
                """;

        PolyvSubtitleSnapshot snapshot = client.fetchTask("522143");

        assertThat(snapshot.state()).isEqualTo(PolyvTaskState.READY);
        assertThat(snapshot.taskId()).isEqualTo(522143L);
        assertThat(snapshot.subtitleName()).isEqualTo("智能字幕");
        assertThat(snapshot.link()).isEqualTo("https://img.videocc.net/x.srt");
        assertThat(snapshot.cues()).hasSize(2);
        assertThat(snapshot.cues().get(0).startMs()).isEqualTo(680);
        assertThat(snapshot.cues().get(0).endMs()).isEqualTo(1240);
        assertThat(snapshot.cues().get(0).content()).isEqualTo("大家好");

        Map<String, String> params = queryParams(lastQuery);
        assertThat(params).containsOnlyKeys("appId", "timestamp", "taskId", "sign");
        Map<String, String> withoutSign = new LinkedHashMap<>(params);
        withoutSign.remove("sign");
        assertThat(params.get("sign")).isEqualTo(expectedSign(withoutSign));
    }

    @Test
    @DisplayName("查询任务：56812 是生成中，不是失败")
    void treatsProcessingAsState() {
        stubStatus = 400;
        stubBody = "{\"code\":400,\"error\":{\"code\":56812,\"desc\":\"字幕正在生成中\"}}";

        PolyvSubtitleSnapshot snapshot = client.fetchTask("522143");

        assertThat(snapshot.state()).isEqualTo(PolyvTaskState.PROCESSING);
        assertThat(snapshot.cues()).isEmpty();
    }

    @Test
    @DisplayName("查询任务：56810 -> 404 POLYV_TASK_NOT_FOUND")
    void mapsTaskNotFound() {
        stubStatus = 400;
        stubBody = "{\"code\":400,\"error\":{\"code\":56810,\"desc\":\"字幕任务不存在或已删除\"}}";

        assertThatThrownBy(() -> client.fetchTask("999999999"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(code((ApiException) e)).isEqualTo("POLYV_TASK_NOT_FOUND"));
    }

    @Test
    @DisplayName("查询任务：非 JSON 响应不乱猜，报 502")
    void rejectsNonJsonBody() {
        stubStatus = 500;
        stubBody = "<html>gateway error</html>";

        assertThatThrownBy(() -> client.fetchTask("522143"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(code((ApiException) e)).isEqualTo("POLYV_UPSTREAM_ERROR"));
    }

    @Test
    @DisplayName("查询任务：字幕条数超过上限 -> 422，不静默截断")
    void rejectsTooManyCues() {
        stubBody = """
                {"code":200,"data":{"taskId":522143,"subtitles":[
                  {"start":0,"end":1000,"content":"a"},
                  {"start":1000,"end":2000,"content":"b"},
                  {"start":2000,"end":3000,"content":"c"},
                  {"start":3000,"end":4000,"content":"d"}]}}
                """;

        assertThatThrownBy(() -> client.fetchTask("522143"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(code((ApiException) e)).isEqualTo("POLYV_SUBTITLE_INVALID"));
    }

    @Test
    @DisplayName("查询任务：缺少毫秒字段 -> 422，不做补零兜底")
    void rejectsCueWithoutTiming() {
        stubBody = "{\"code\":200,\"data\":{\"taskId\":522143,\"subtitles\":[{\"content\":\"a\"}]}}";

        assertThatThrownBy(() -> client.fetchTask("522143"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(code((ApiException) e)).isEqualTo("POLYV_SUBTITLE_INVALID"));
    }

    @Test
    @DisplayName("查询任务：非数字 taskId 直接拒绝，不发请求")
    void rejectsNonNumericTaskId() {
        assertThatThrownBy(() -> client.fetchTask("abc"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(code((ApiException) e)).isEqualTo("SUBTITLE_TASK_NOT_FOUND"));
        assertThat(lastQuery).isNull();
    }

    @Test
    @DisplayName("查询任务：返回条数上限内正常解析（边界不误判）")
    void acceptsCuesUpToLimit() {
        stubBody = """
                {"code":200,"data":{"taskId":522143,"subtitles":[
                  {"start":0,"end":1000,"content":"a"},
                  {"start":1000,"end":2000,"content":"b"},
                  {"start":2000,"end":3000,"content":"c"}]}}
                """;

        assertThat(client.fetchTask("522143").cues()).hasSize(3);
    }

    @Test
    @DisplayName("空字符串语言按不支持处理（不落到平台默认值）")
    void requiresExplicitLanguage() {
        assertThatThrownBy(() -> client.createTask("9df0_testvid", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(List.of("chinese", "english")).isNotEmpty();
    }
}
