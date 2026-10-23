package com.liveproof.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 媒体白名单配置（liveproof.media.*）。
 *
 * <p>红线：白名单必须是<b>显式枚举的文件名</b>。
 * 启动时校验，出现通配符、目录路径、路径分隔符或非法后缀即拒绝启动，
 * 避免被改成一个"通用静态目录"从而误暴露带第三方水印的素材。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "liveproof.media")
public class MediaProperties {

    private static final Logger log = LoggerFactory.getLogger(MediaProperties.class);

    /** 素材根目录（相对工作目录或绝对路径）。 */
    private String root = "../liveproof-demo/owned-demo";

    /** 允许对外提供的视频文件名（必须显式枚举）。 */
    private List<String> allowedFiles = List.of();

    /** 允许解析的字幕文件名（必须显式枚举）。 */
    private List<String> allowedSubtitles = List.of();

    /** 视频名 -> 字幕名 的显式映射。 */
    private Map<String, String> subtitleMapping = new LinkedHashMap<>();

    private Path rootPath;

    @PostConstruct
    public void init() {
        validateEntries(allowedFiles, ".mp4", "liveproof.media.allowed-files");
        validateEntries(allowedSubtitles, ".srt", "liveproof.media.allowed-subtitles");
        subtitleMapping.forEach((video, srt) -> {
            if (!allowedFiles.contains(video)) {
                throw new IllegalStateException(
                        "配置错误：liveproof.media.subtitle-mapping 的键 " + video
                                + " 不在 allowed-files 中");
            }
            if (!allowedSubtitles.contains(srt)) {
                throw new IllegalStateException(
                        "配置错误：liveproof.media.subtitle-mapping 的值 " + srt
                                + " 不在 allowed-subtitles 中");
            }
        });

        this.rootPath = Path.of(root).toAbsolutePath().normalize();
        if (!Files.isDirectory(rootPath)) {
            log.warn("媒体根目录不存在：{}（媒体接口将返回 404，其余接口不受影响）", rootPath);
        } else {
            log.info("媒体根目录：{}，白名单视频 {} 个，白名单字幕 {} 个",
                    rootPath, allowedFiles.size(), allowedSubtitles.size());
        }
    }

    private static void validateEntries(List<String> entries, String suffix, String key) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalStateException("配置错误：" + key + " 不能为空，必须显式枚举文件名");
        }
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                throw new IllegalStateException("配置错误：" + key + " 含空项");
            }
            if (entry.contains("*") || entry.contains("/") || entry.contains("\\")
                    || entry.contains("..")) {
                throw new IllegalStateException(
                        "配置错误：" + key + " 不允许通配符、目录或路径片段：" + entry);
            }
            if (!entry.toLowerCase().endsWith(suffix)) {
                throw new IllegalStateException(
                        "配置错误：" + key + " 只接受 " + suffix + " 文件：" + entry);
            }
        }
    }

    /** 解析白名单内的视频路径；不在白名单返回 empty。 */
    public Optional<Path> resolveVideo(String name) {
        return resolve(name, allowedFiles);
    }

    /** 解析白名单内的字幕路径；不在白名单返回 empty。 */
    public Optional<Path> resolveSubtitle(String name) {
        return resolve(name, allowedSubtitles);
    }

    /** 视频名对应的字幕名（仅白名单内）。 */
    public Optional<String> subtitleNameOf(String videoName) {
        return Optional.ofNullable(subtitleMapping.get(videoName));
    }

    private Optional<Path> resolve(String name, List<String> allowed) {
        if (name == null || !allowed.contains(name)) {
            return Optional.empty();
        }
        Path candidate = rootPath.resolve(name).normalize();
        // 纵深防御：即使白名单被误配，也不允许跳出根目录
        if (!candidate.startsWith(rootPath)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(candidate)) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }
}
