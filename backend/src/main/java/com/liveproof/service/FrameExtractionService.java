package com.liveproof.service;

import com.liveproof.config.AiProperties;
import com.liveproof.config.MediaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关键帧提取（FFmpeg 子进程）。
 *
 * <p>安全约束：
 * <ul>
 *   <li>只处理 {@link MediaProperties} 白名单内的视频；文件名由服务端根据用例记录取，
 *       客户端无法传入服务器路径</li>
 *   <li>使用参数数组启动进程，<b>不拼接 shell 命令</b></li>
 *   <li>时间点、帧数、图片宽度、单帧字节、进程超时均受上限约束</li>
 *   <li>通过 stdout 管道取图，<b>不产生临时文件</b>，因此不存在残留清理问题</li>
 * </ul>
 *
 * <p>失败语义：返回 {@code available=false} 与原因，由上层在分析结果里明确写出
 * 「本次未完成画面核验」，绝不假装看过画面。
 */
@Service
public class FrameExtractionService {

    private static final Logger log = LoggerFactory.getLogger(FrameExtractionService.class);

    private static final Pattern DURATION = Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");

    /** 一帧画面：时间点 + JPEG 字节 + 实际像素尺寸。 */
    public record Frame(double sec, byte[] jpeg, int width, int height) {
    }

    /**
     * 提取结果。
     *
     * @param available 是否至少取到一帧可用画面
     * @param note      不可用/降级的原因（会展示给用户）
     */
    public record Extraction(boolean available, String note, List<Frame> frames) {
    }

    private final MediaProperties mediaProperties;
    private final AiProperties aiProperties;
    private final Map<String, Double> durationCache = new ConcurrentHashMap<>();
    private final AtomicReference<Boolean> ffmpegAvailable = new AtomicReference<>(null);

    public FrameExtractionService(MediaProperties mediaProperties, AiProperties aiProperties) {
        this.mediaProperties = mediaProperties;
        this.aiProperties = aiProperties;
    }

    /** FFmpeg 是否可用（结果缓存，避免每次分析都起进程探测）。 */
    public boolean isFfmpegAvailable() {
        Boolean cached = ffmpegAvailable.get();
        if (cached != null) {
            return cached;
        }
        boolean ok;
        try {
            Process process = new ProcessBuilder(List.of(ffmpeg(), "-version"))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process.getOutputStream().close();
            ok = process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
            if (!ok) {
                process.destroyForcibly();
            }
        } catch (Exception e) {
            ok = false;
            log.warn("FFmpeg 不可用：{}（提示：可用 LIVEPROOF_FFMPEG_PATH 指定绝对路径）", e.getMessage());
        }
        ffmpegAvailable.set(ok);
        return ok;
    }

    /** 读取视频时长（秒）；解析失败返回 null（调用方需容忍）。 */
    public Double durationOf(Path video) {
        String key = video.toString() + ":" + safeSize(video);
        Double cached = durationCache.get(key);
        if (cached != null) {
            return cached;
        }
        if (!isFfmpegAvailable()) {
            return null;
        }
        try {
            Process process = new ProcessBuilder(List.of(ffmpeg(), "-hide_banner", "-nostdin", "-i", video.toString()))
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();
            byte[] output;
            try (InputStream in = process.getInputStream()) {
                output = readCapped(in, 64 * 1024);
            }
            process.waitFor(8, TimeUnit.SECONDS);
            process.destroy();
            Matcher matcher = DURATION.matcher(new String(output, StandardCharsets.UTF_8));
            if (matcher.find()) {
                double seconds = Integer.parseInt(matcher.group(1)) * 3600.0
                        + Integer.parseInt(matcher.group(2)) * 60.0
                        + Double.parseDouble(matcher.group(3));
                if (seconds > 0) {
                    durationCache.put(key, seconds);
                    return seconds;
                }
            }
        } catch (Exception e) {
            log.warn("读取视频时长失败：{}", e.getMessage());
        }
        return null;
    }

    /**
     * 按给定时间点提取关键帧。
     *
     * @param videoName 用例记录里的视频文件名（必须命中白名单）
     * @param times     候选时间点（秒）；超过配置上限的部分会被截断
     */
    public Extraction extract(String videoName, List<Double> times) {
        // 先做白名单校验：不在白名单的视频连探测进程都不起，拒绝理由也最准确
        Optional<Path> resolved = mediaProperties.resolveVideo(videoName);
        if (resolved.isEmpty()) {
            return new Extraction(false,
                    "该视频不在媒体白名单内，本次未做画面核验（仅基于字幕判断）", List.of());
        }
        if (!isFfmpegAvailable()) {
            return new Extraction(false,
                    "未找到可用的 FFmpeg，本次未做画面核验（仅基于字幕判断）", List.of());
        }
        Path video = resolved.get();
        Double duration = durationOf(video);

        List<Frame> frames = new ArrayList<>();
        int failed = 0;
        for (Double time : times) {
            if (frames.size() >= Math.max(1, aiProperties.getMaxFrames())) {
                break;
            }
            if (time == null || time < 0) {
                continue;
            }
            double sec = Math.round(time * 1000.0) / 1000.0;
            if (duration != null && sec > duration - 0.05) {
                continue;
            }
            byte[] jpeg = grabFrame(video, sec);
            if (jpeg == null) {
                failed++;
                continue;
            }
            int[] size = jpegSize(jpeg);
            frames.add(new Frame(sec, jpeg,
                    size == null ? 0 : size[0],
                    size == null ? 0 : size[1]));
        }

        if (frames.isEmpty()) {
            return new Extraction(false,
                    "关键帧提取失败" + (failed > 0 ? "（" + failed + " 个时间点均未取到画面）" : "")
                            + "，本次未做画面核验（仅基于字幕判断）", List.of());
        }
        String note = failed > 0 ? failed + " 个时间点未取到画面，已跳过" : null;
        return new Extraction(true, note, frames);
    }

    /** 单个时间点取一帧；任何失败都返回 null，不抛异常打断整体分析。 */
    private byte[] grabFrame(Path video, double sec) {
        List<String> command = List.of(
                ffmpeg(),
                "-hide_banner", "-loglevel", "error", "-nostdin",
                "-ss", String.format(Locale.ROOT, "%.3f", sec),
                "-i", video.toString(),
                "-frames:v", "1",
                // 逗号在 filtergraph 里是分隔符，必须转义
                "-vf", "scale=min(" + Math.max(160, aiProperties.getFrameMaxWidth()) + "\\,iw):-2",
                "-q:v", "4",
                "-f", "image2", "-c:v", "mjpeg",
                "pipe:1");
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process.getOutputStream().close();
            Process running = process;
            AtomicReference<byte[]> holder = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                try (InputStream in = running.getInputStream()) {
                    holder.set(readCapped(in, aiProperties.getFrameMaxBytes()));
                } catch (IOException ignored) {
                    // 输出被截断或进程提前退出，交给下面的校验判断
                }
            }, "ffmpeg-frame-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(aiProperties.getFfmpegTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("关键帧提取超时：{}s", sec);
                return null;
            }
            reader.join(1000);
            byte[] data = holder.get();
            if (process.exitValue() != 0 || data == null || data.length < 4) {
                return null;
            }
            // JPEG 魔数校验：防止把错误文本当成图片提交给模型
            if ((data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
                return null;
            }
            return data;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("关键帧提取失败（{}s）：{}", sec, e.getMessage());
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String ffmpeg() {
        String path = aiProperties.getFfmpegPath();
        return path == null || path.isBlank() ? "ffmpeg" : path;
    }

    private static long safeSize(Path path) {
        try {
            return java.nio.file.Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }

    private static byte[] readCapped(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            int room = cap - out.size();
            if (room <= 0) {
                break;
            }
            out.write(buffer, 0, Math.min(read, room));
        }
        return out.toByteArray();
    }

    /**
     * 从 JPEG 的 SOF 段读取像素尺寸。
     *
     * <p>只用于把"模型看到的画面尺寸"如实回传，不参与任何业务判断；
     * 解析失败返回 null，调用方退化为 0。
     */
    static int[] jpegSize(byte[] data) {
        if (data == null || data.length < 4) {
            return null;
        }
        int i = 2;
        while (i + 9 < data.length) {
            if ((data[i] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            int marker = data[i + 1] & 0xFF;
            if (marker == 0xD8 || marker == 0xD9) {
                i += 2;
                continue;
            }
            int length = ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
            boolean sof = marker >= 0xC0 && marker <= 0xCF
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof) {
                int height = ((data[i + 5] & 0xFF) << 8) | (data[i + 6] & 0xFF);
                int width = ((data[i + 7] & 0xFF) << 8) | (data[i + 8] & 0xFF);
                return new int[]{width, height};
            }
            i += 2 + length;
        }
        return null;
    }
}
