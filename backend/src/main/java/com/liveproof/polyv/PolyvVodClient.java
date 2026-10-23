package com.liveproof.polyv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liveproof.config.PolyvProperties;
import com.liveproof.exception.MaterialErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 保利威点播 v2 客户端：上传视频、查询视频信息、指定时间点截图。
 *
 * <p>与 {@link PolyvSubtitleClient}（v4 智能字幕，MD5）是两套签名，混用会 10002/400。
 * 本类统一用 v2：{@code SHA1(升序 k=v& 拼串 + secretkey)} 40 位大写。
 *
 * <p>已实测（2026-10-23）：
 * <ul>
 *   <li>上传 356KB 耗时 963ms，返回 {@code {error:0,data:[{vid,status:"10",duration:"00:00:08"}]}}</li>
 *   <li>转码：status 10 →(2.4s) 20 →(15.2s) 50 等待审核；<b>50 时 playUrl 已可 206 播放</b></li>
 *   <li>截图：addTask 立即返回 taskId、状态 {@code waiting_hw}，约 2s 后 success，结果
 *       {@code snapshots:[{offsetTime:1,imageUrl:".../1.jpg"}]}，offsetTime 为<b>整数秒</b></li>
 *   <li>截图 {@code width} 超过源视频宽度会返回 {@code 400 width over limit}，故取 min(配置值, 源宽)</li>
 *   <li><b>只传 width 会横向压扁画面</b>（实测 2026-10-23）：{@code width=720} 时返回 720×1080，
 *       高度保持源高的 1080 不变，宽高比从 1.778 变成 0.667，即横向被压缩 2.67 倍。
 *       该接口按 width、height 各自独立缩放，<b>必须同时传 height</b> 才能保持比例。</li>
 *   <li><b>截图结果按秒永久缓存</b>：同一 vid 的同一秒只要生成过一次，后续请求直接返回同一 URL
 *       的旧文件，width/height 不再生效。因此一旦用错误的 height 取过某秒，该秒就永久变形，
 *       改参数也救不回来（只能换时间点或用本地 FFmpeg 重取）。这条是「参数必须先算对」的硬理由。</li>
 * </ul>
 *
 * <p>安全：writetoken / secretkey 只参与请求构造与签名，不写日志、不进异常 message。
 */
@Component
public class PolyvVodClient {

    private static final Logger log = LoggerFactory.getLogger(PolyvVodClient.class);

    private static final String UPLOAD_PATH = "/uc/services/rest?method=uploadfile";
    private static final String BOUNDARY = "----LiveProofBoundary" + UUID.randomUUID().toString().replace("-", "");
    private static final long IMAGE_MAX_BYTES = 3L * 1024 * 1024;

    private final PolyvProperties props;
    private final PolyvSigner signer;
    private final ObjectMapper mapper;

    public PolyvVodClient(PolyvProperties props, PolyvSigner signer, ObjectMapper mapper) {
        this.props = props;
        this.signer = signer;
        this.mapper = mapper;
    }

    public boolean isUploadConfigured() {
        return props.uploadConfigured();
    }

    public boolean isConfigured() {
        return props.vodConfigured();
    }

    // ── 上传 ──────────────────────────────────────────────

    /** 上传结果。{@code durationSec} 可能为 null（平台只给 HH:MM:SS）。 */
    public record UploadResult(String vid, int status, Double durationSec) {
    }

    /**
     * 把本地文件上传到保利威点播。
     *
     * <p>实现说明：把 multipart 请求体（前导头 + 文件字节 + 结尾）拼到一个临时文件中发送，
     * 以便带上准确的 {@code Content-Length}（流式 chunked 上传在部分网关会被拒）。
     * 临时文件在返回前删除。
     *
     * @param file  待上传文件（调用方负责校验与删除）
     * @param title 视频标题（写入 JSONRPC）
     */
    public UploadResult upload(Path file, String title) {
        if (!props.uploadConfigured()) {
            throw MaterialErrors.uploadNotConfigured();
        }
        long size = -1;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw MaterialErrors.uploadFailed(-1, "读取本地临时文件失败");
        }

        Path body = null;
        try {
            body = Files.createTempFile("liveproof-upload-", ".body");
            writeMultipartBody(body, file, title, size);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(props.getUploadBaseUrl() + UPLOAD_PATH))
                    .timeout(Duration.ofMillis(props.getUploadTimeoutMs()))
                    .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                    .POST(HttpRequest.BodyPublishers.ofFile(body))
                    .build();

            long started = System.currentTimeMillis();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsed = System.currentTimeMillis() - started;

            UploadResult result = parseUpload(response.body());
            log.info("保利威上传完成：vid={} status={} 字节={} 耗时={}ms",
                    result.vid(), result.status(), size, elapsed);
            return result;
        } catch (java.net.http.HttpTimeoutException e) {
            throw MaterialErrors.uploadTimeout();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw MaterialErrors.uploadFailed(-1, "网络异常，未收到保利威响应");
        } finally {
            deleteQuietly(body);
        }
    }

    private void writeMultipartBody(Path target, Path file, String title, long size) throws IOException {
        String jsonrpc = mapper.writeValueAsString(Map.of("title", title, "tag", "liveproof"));
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target), 1 << 16)) {
            out.write(field("writetoken", props.getWriteToken()));
            out.write(field("JSONRPC", jsonrpc));
            // 避免中文文件名乱码；标题已走 JSONRPC，这里固定 ASCII 文件名
            out.write(field("fcharset", "ISO-8859-1"));
            out.write(("--" + BOUNDARY + "\r\n"
                    + "Content-Disposition: form-data; name=\"Filedata\"; filename=\"upload.mp4\"\r\n"
                    + "Content-Type: video/mp4\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            Files.copy(file, out);
            out.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] field(String name, String value) {
        return ("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private UploadResult parseUpload(String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            log.warn("保利威上传返回非 JSON，长度 {}", body == null ? 0 : body.length());
            throw MaterialErrors.uploadFailed(-1, "响应不是合法 JSON");
        }
        int error = root.path("error").asInt(-1);
        if (error != 0) {
            throw MaterialErrors.uploadFailed(error, uploadErrorText(error));
        }
        JsonNode data = root.path("data");
        JsonNode item = data.isArray() ? data.path(0) : data.path("video");
        String vid = item.path("vid").asText(null);
        if (vid == null || vid.isBlank()) {
            throw MaterialErrors.uploadFailed(0, "响应缺少 vid");
        }
        int status = item.path("status").asInt(10);
        return new UploadResult(vid, status, parseHms(item.path("duration").asText(null)));
    }

    /** 平台错误码 -> 可读说明（官方《上传视频》返回错误列表）。 */
    private static String uploadErrorText(int code) {
        return switch (code) {
            case 1 -> "找不到 writetoken 关联的用户";
            case 2 -> "文件为空或 writetoken 为空";
            case 3 -> "缺少 JSONRPC";
            case 4 -> "文件格式不正确或超过 2G";
            case 7 -> "vid 不能为空";
            case 14 -> "文件 MD5 校验失败";
            case 15 -> "平台后台处理出错";
            case 16 -> "账号可用空间已用完";
            default -> "未知错误";
        };
    }

    // ── 视频信息 ──────────────────────────────────────────

    /** 视频信息（只取本项目需要的字段）。 */
    public record VideoInfo(int status, Double durationSec, Long sizeBytes, Integer width, Integer height, String playUrl) {

        /** 已发布（60/61）。50 等待审核时 playUrl 已可播，但未发布。 */
        public boolean published() {
            return status == 60 || status == 61;
        }
    }

    public VideoInfo videoInfo(String vid) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vid", vid);
        params.put("ptime", String.valueOf(System.currentTimeMillis()));
        String url = props.getBaseUrl() + "/v2/video/" + props.getUserId() + "/get-video-info?"
                + query(params) + "&sign=" + signer.signV2(params, props.getSecretKey());

        JsonNode root = getJson(url);
        if (root.path("code").asInt(-1) != 200) {
            throw MaterialErrors.uploadFailed(root.path("code").asInt(-1),
                    "查询视频信息失败：" + root.path("message").asText(""));
        }
        JsonNode data = root.path("data").path(0);
        JsonNode basic = data.path("basicInfo");
        JsonNode meta = data.path("metaData");
        int status = basic.path("status").asInt(-1);
        String playUrl = pickPlayUrl(data.path("transcodeInfos"), status);
        return new VideoInfo(
                status,
                basic.path("duration").isNumber() ? basic.path("duration").asDouble() : null,
                basic.path("size").isNumber() ? basic.path("size").asLong() : null,
                meta.path("width").isNumber() ? meta.path("width").asInt() : null,
                meta.path("height").isNumber() ? meta.path("height").asInt() : null,
                playUrl);
    }

    /**
     * 从 transcodeInfos 里挑一个可播放的 mp4 直链。
     *
     * <p>实测（2026-10-23）两个坑：
     * <ul>
     *   <li>等待审核（basicInfo.status=50）时 {@code transcodeInfos[].status} 是 {@code "unavailable"}，
     *       但 playUrl <b>已经可以 200/206 播放</b>（curl 实测 3591967 字节可下载）。所以不能只认
     *       {@code "normal"}，否则素材刚转码完就报「尚未给出播放地址」。</li>
     *   <li>只接受 {@code format=mp4} 且 {@code encrypt=false} 的直链；加密地址浏览器 <video> 放不了，
     *       会让前端白屏而不报错。</li>
     * </ul>
     *
     * <p>优先取 {@code status=normal} 的条目（已发布），没有则退化为任意可用 mp4 直链。
     */
    private String pickPlayUrl(JsonNode transcodeInfos, int videoStatus) {
        String fallback = null;
        for (JsonNode transcode : transcodeInfos) {
            String candidate = transcode.path("playUrl").asText("");
            if (candidate.isBlank()) {
                continue;
            }
            if (!"mp4".equalsIgnoreCase(transcode.path("format").asText("mp4"))) {
                continue;
            }
            if (transcode.path("encrypt").asBoolean(false)) {
                continue;
            }
            if ("normal".equals(transcode.path("status").asText(""))) {
                return candidate;
            }
            if (fallback == null) {
                fallback = candidate;
            }
        }
        if (fallback != null) {
            log.debug("保利威视频 status={} 的转码条目标记为不可用，但直链实测可播，按可用返回", videoStatus);
        }
        return fallback;
    }

    // ── 指定时间点截图 ────────────────────────────────────

    /** 截图结果：{@code offsetSec} 为平台实际取帧的整数秒。 */
    public record Snapshot(int offsetSec, String imageUrl) {
    }

    /**
     * 截图请求的像素尺寸。
     *
     * <p>两个参数必须成对下发：该接口对 width、height 各自独立缩放，
     * 只给 width 会让高度停在源高，画面被横向压扁。
     */
    public record Size(int width, int height) {
    }

    /**
     * 计算截图尺寸：宽度取 min(配置上限, 源宽)，高度按源宽高比等比推导。
     *
     * <p>源尺寸未知时返回 {@code null}，即不下发尺寸参数、由平台返回原图——
     * 原图比例一定正确，比猜一个压扁的尺寸安全；后续交给模型前也不需要额外解码。
     */
    static Size snapshotSize(int maxWidth, Integer sourceWidth, Integer sourceHeight) {
        if (sourceWidth == null || sourceHeight == null || sourceWidth <= 0 || sourceHeight <= 0) {
            return null;
        }
        int width = Math.max(1, Math.min(maxWidth, sourceWidth));
        // 高度按同比例缩放，且不超过源高；源本身已小于目标宽时宽度就是源宽，高度即源高
        long height = Math.round((double) width * sourceHeight / sourceWidth);
        return new Size(width, (int) Math.max(1, Math.min(height, sourceHeight)));
    }

    /**
     * 申请指定时间点的截图并等待完成。
     *
     * @param offsets     时间点（秒）；方法内部四舍五入为整数秒、去重、按升序截断到上限
     * @param maxWidth    期望宽度；实际取 min(maxWidth, 源视频宽度)，避免 "width over limit"
     * @param sourceWidth 源视频宽度（用于等比计算高度与宽度上限）
     * @param sourceHeight 源视频高度（用于等比计算高度；未知时为 null）
     *
     * <p><b>为什么必须同时传 height</b>：该接口对 width、height 是各自独立缩放，
     * 只传 width 会导致高度保持源高、画面横向压扁（实测 width=720 → 720×1080）。
     * 而截图结果按秒永久缓存，第一次取错就永久变形，所以参数必须一次算对。
     */
    public List<Snapshot> snapshot(String vid, List<Double> offsets, int maxWidth,
                                   Integer sourceWidth, Integer sourceHeight) {
        if (!props.vodConfigured()) {
            throw MaterialErrors.snapshotFailed("点播凭据未配置");
        }
        List<Integer> seconds = normalizeOffsets(offsets);
        if (seconds.isEmpty()) {
            return List.of();
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("vid", vid);
        params.put("offsetTimes", joinInts(seconds));
        params.put("ptime", String.valueOf(System.currentTimeMillis()));
        Size size = snapshotSize(maxWidth, sourceWidth, sourceHeight);
        if (size != null) {
            params.put("width", String.valueOf(size.width()));
            params.put("height", String.valueOf(size.height()));
        }

        Map<String, String> body = new LinkedHashMap<>(params);
        body.put("sign", signer.signV2(params, props.getSecretKey()));
        JsonNode created = postForm(props.getBaseUrl() + "/v2/video/snapshot/" + props.getUserId() + "/addTask", body);
        if (created.path("code").asInt(-1) != 200) {
            throw MaterialErrors.snapshotFailed(created.path("message").asText("addTask 返回失败"));
        }
        long taskId = created.path("data").asLong(-1);
        if (taskId <= 0) {
            throw MaterialErrors.snapshotFailed("addTask 未返回 taskId");
        }
        return awaitSnapshots(taskId);
    }

    private List<Snapshot> awaitSnapshots(long taskId) {
        long deadline = System.currentTimeMillis() + props.getSnapshotTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("taskId", String.valueOf(taskId));
            params.put("ptime", String.valueOf(System.currentTimeMillis()));
            String url = props.getBaseUrl() + "/v2/video/snapshot/" + props.getUserId() + "/getTaskStatus?"
                    + query(params) + "&sign=" + signer.signV2(params, props.getSecretKey());
            JsonNode root = getJson(url);
            if (root.path("code").asInt(-1) != 200) {
                throw MaterialErrors.snapshotFailed("查询截图任务失败：" + root.path("message").asText(""));
            }
            JsonNode data = root.path("data");
            String status = data.path("status").asText("");
            JsonNode snapshots = data.path("snapshots");
            if (snapshots.isArray() && !snapshots.isEmpty()) {
                List<Snapshot> result = new ArrayList<>();
                for (JsonNode node : snapshots) {
                    result.add(new Snapshot(node.path("offsetTime").asInt(0), node.path("imageUrl").asText("")));
                }
                log.info("保利威截图完成：taskId={} 帧数={}", taskId, result.size());
                return result;
            }
            if (status.toLowerCase().contains("fail")) {
                throw MaterialErrors.snapshotFailed("任务状态 " + status);
            }
            try {
                Thread.sleep(props.getSnapshotPollIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw MaterialErrors.snapshotFailed("等待截图结果时被中断");
            }
        }
        throw MaterialErrors.snapshotFailed("等待截图结果超时（" + props.getSnapshotTimeoutMs() + "ms）");
    }

    /** 下载截图（服务端转 base64 用，客户端拿不到该 URL）。 */
    public byte[] downloadImage(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith("http")) {
            throw MaterialErrors.snapshotFailed("截图地址非法");
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .timeout(Duration.ofMillis(props.getTimeoutMs()))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes = response.body();
            if (response.statusCode() != 200 || bytes == null || bytes.length == 0) {
                throw MaterialErrors.snapshotFailed("截图下载失败（HTTP " + response.statusCode() + "）");
            }
            if (bytes.length > IMAGE_MAX_BYTES) {
                throw MaterialErrors.snapshotFailed("截图过大：" + bytes.length + " 字节");
            }
            if ((bytes[0] & 0xFF) != 0xFF || (bytes[1] & 0xFF) != 0xD8) {
                throw MaterialErrors.snapshotFailed("下载内容不是 JPEG");
            }
            return bytes;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw MaterialErrors.snapshotFailed("截图下载网络异常");
        }
    }

    // ── 内部工具 ──────────────────────────────────────────

    private JsonNode getJson(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(props.getTimeoutMs()))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return mapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw MaterialErrors.snapshotFailed("调用保利威接口网络异常");
        }
    }

    private JsonNode postForm(String url, Map<String, String> form) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(props.getTimeoutMs()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(query(form), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return mapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw MaterialErrors.snapshotFailed("调用保利威接口网络异常");
        }
    }

    private static String query(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (v == null || v.isEmpty()) {
                return;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(k).append('=').append(urlEncode(v));
        });
        return sb.toString();
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String joinInts(List<Integer> values) {
        StringBuilder sb = new StringBuilder();
        for (Integer v : values) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(v);
        }
        return sb.toString();
    }

    /** 四舍五入到整秒、去掉负数、去重升序、截断到平台上限。 */
    private List<Integer> normalizeOffsets(List<Double> offsets) {
        return offsets.stream()
                .filter(java.util.Objects::nonNull)
                .map(v -> Math.max(0, (int) Math.round(v)))
                .distinct()
                .sorted()
                .limit(props.getSnapshotMaxTimes())
                .toList();
    }

    /** "00:00:08" -> 8.0；解析失败返回 null（不猜）。 */
    static Double parseHms(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] parts = text.trim().split(":");
        try {
            if (parts.length == 3) {
                return Integer.parseInt(parts[0]) * 3600.0
                        + Integer.parseInt(parts[1]) * 60.0
                        + Double.parseDouble(parts[2]);
            }
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件删不掉不影响业务，交给系统清理
        }
    }

    /** 供测试注入小文件时使用（避免测试触碰真实文件系统语义）。 */
    public static void copyTo(Path from, Path to) throws IOException {
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
}
