package com.liveproof.polyv;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liveproof.config.PolyvProperties;
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

/**
 * 指定时间点截图的尺寸计算与参数下发测试。
 *
 * <p>背景（2026-10-23 实测）：该接口对 {@code width}、{@code height} 是<b>各自独立缩放</b>。
 * 只传 {@code width=720} 时返回 {@code 720×1080}（源 1920×1080），画面横向被压缩 2.67 倍。
 * 更糟的是<b>截图结果按秒永久缓存</b>，同一 vid 同一秒一旦生成过，改参数再请求仍返回旧文件——
 * 参数取错不可逆。所以这里既测计算结果，也测「是否真的成对下发」。
 */
class PolyvVodSnapshotSizeTest {

    private static final String USER_ID = "9df0de1ea2";
    private static final String SECRET_KEY = "Vk9secretVk9";

    private static HttpServer server;
    private static String baseUrl;

    /** addTask 的返回：taskId 是裸数字，与 getTaskStatus 的结构完全不同，必须分开桩。 */
    private static final String ADD_TASK_BODY = "{\"code\":200,\"data\":522143}";

    private static volatile String addTaskPath;
    private static volatile String addTaskBody;
    private static volatile String statusBody;
    private static volatile int statusStubStatus;

    private PolyvVodClient client;
    private PolyvProperties props;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/video/snapshot/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            boolean addTask = path.endsWith("/addTask");
            if (addTask) {
                // 只记录 addTask：轮询 getTaskStatus 会反复覆盖同一个字段，导致断言拿到错的路径
                addTaskPath = path;
                addTaskBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            }
            byte[] payload = (addTask ? ADD_TASK_BODY : statusBody).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(addTask ? 200 : statusStubStatus, payload.length);
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
        statusStubStatus = 200;
        statusBody = """
                {"code":200,"data":{"status":"success","snapshots":[
                  {"offsetTime":23,"imageUrl":"http://127.0.0.1/23.jpg"}]}}
                """;
        addTaskPath = null;
        addTaskBody = null;

        props = new PolyvProperties();
        props.setUserId(USER_ID);
        props.setSecretKey(SECRET_KEY);
        props.setBaseUrl(baseUrl);
        props.setConnectTimeoutMs(2000);
        props.setTimeoutMs(3000);
        props.setSnapshotWidth(720);
        props.setSnapshotMaxTimes(20);
        props.setSnapshotPollIntervalMs(1);
        props.setSnapshotTimeoutMs(5000);
        client = new PolyvVodClient(props, new PolyvSigner(props), new ObjectMapper());
    }

    @AfterEach
    void clearStub() {
        statusStubStatus = 200;
    }

    // ------------------------------------------------------------ 尺寸计算

    @Test
    @DisplayName("16:9 源视频：720 宽必须推出 405 高，而不是沿用源高 1080")
    void keepsAspectRatio() {
        PolyvVodClient.Size size = PolyvVodClient.snapshotSize(720, 1920, 1080);

        assertThat(size).isNotNull();
        assertThat(size.width()).isEqualTo(720);
        assertThat(size.height()).isEqualTo(405);
        // 宽高比必须与源一致（16:9 = 1.778）
        assertThat((double) size.width() / size.height()).isCloseTo(16.0 / 9.0, within(0.01));
    }

    @Test
    @DisplayName("源宽小于目标宽时不放大，直接用源尺寸（同时避开 width over limit）")
    void doesNotUpscale() {
        PolyvVodClient.Size size = PolyvVodClient.snapshotSize(720, 640, 360);

        assertThat(size).isNotNull();
        assertThat(size.width()).isEqualTo(640);
        assertThat(size.height()).isEqualTo(360);
    }

    @Test
    @DisplayName("竖屏源视频：比例同样保持（9:16）")
    void handlesPortrait() {
        PolyvVodClient.Size size = PolyvVodClient.snapshotSize(720, 1080, 1920);

        assertThat(size).isNotNull();
        assertThat(size.width()).isEqualTo(720);
        assertThat(size.height()).isEqualTo(1280);
        assertThat((double) size.width() / size.height()).isCloseTo(9.0 / 16.0, within(0.01));
    }

    @Test
    @DisplayName("源尺寸未知时不下发尺寸，交回原图（原图比例一定正确）")
    void skipsSizeWhenSourceUnknown() {
        assertThat(PolyvVodClient.snapshotSize(720, null, 1080)).isNull();
        assertThat(PolyvVodClient.snapshotSize(720, 1920, null)).isNull();
        assertThat(PolyvVodClient.snapshotSize(720, 0, 0)).isNull();
    }

    // ------------------------------------------------------------ 参数下发

    @Test
    @DisplayName("addTask 请求里 width 与 height 必须成对出现，且高度是等比算出的 405")
    void sendsWidthAndHeightTogether() {
        client.snapshot("9df0_testvid", List.of(23.4), 720, 1920, 1080);

        assertThat(addTaskPath).isEqualTo("/v2/video/snapshot/" + USER_ID + "/addTask");

        Map<String, String> form = formParams(addTaskBody);
        assertThat(form).containsKeys("width", "height");
        assertThat(form.get("width")).isEqualTo("720");
        assertThat(form.get("height")).isEqualTo("405");
        // offsetTimes 是整数秒：23.4 -> 23
        assertThat(form.get("offsetTimes")).isEqualTo("23");
        // 密钥绝不出现在表单里
        assertThat(addTaskBody).doesNotContain(SECRET_KEY);
    }

    @Test
    @DisplayName("源尺寸未知时请求体里不带 width/height（而不是只带一个）")
    void omitsBothWhenSourceUnknown() {
        client.snapshot("9df0_testvid", List.of(23.0), 720, null, null);

        Map<String, String> form = formParams(addTaskBody);
        assertThat(form).doesNotContainKeys("width", "height");
        assertThat(form).containsKeys("vid", "offsetTimes", "ptime", "sign");
    }

    @Test
    @DisplayName("截图结果按 offsetTime 原样返回，精度由平台决定（整秒）")
    void returnsPlatformOffsets() {
        List<PolyvVodClient.Snapshot> snapshots =
                client.snapshot("9df0_testvid", List.of(23.4), 720, 1920, 1080);

        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).offsetSec()).isEqualTo(23);
        assertThat(snapshots.get(0).imageUrl()).isEqualTo("http://127.0.0.1/23.jpg");
    }

    // ------------------------------------------------------------ 工具

    private static Map<String, String> formParams(String body) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) {
                continue;
            }
            params.put(URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}