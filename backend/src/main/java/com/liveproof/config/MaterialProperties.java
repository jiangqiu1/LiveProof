package com.liveproof.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 素材库配置（liveproof.materials.*）。
 *
 * <p>视频本体存放在保利威点播，本机目录只用于两件事：
 * <ol>
 *   <li>上传字幕 SRT（几十 KB，证据定位要按 cue 二分检索，走网络太慢）</li>
 *   <li>上传 mp4 时的临时中转文件（转发到保利威后立即删除）</li>
 * </ol>
 *
 * <p>安全：目录必须是绝对或相对路径，启动时创建；任何对外响应都不返回该路径。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "liveproof.materials")
public class MaterialProperties {

    private static final Logger log = LoggerFactory.getLogger(MaterialProperties.class);

    /** 本机素材目录（字幕 + 上传中转）。 */
    private String uploadDir = "../liveproof-demo/uploads";

    /** 单个视频上限，默认 200MB。 */
    private long maxBytes = 200L * 1024 * 1024;

    /** 素材总数上限（含内置）。 */
    private int maxCount = 20;

    /** 单个 SRT 上限，默认 1MB。 */
    private long srtMaxBytes = 1024 * 1024;

    public Path root() {
        return Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() {
        if (maxBytes <= 0) {
            throw new IllegalStateException("配置错误：liveproof.materials.max-bytes 必须为正数");
        }
        if (srtMaxBytes <= 0 || srtMaxBytes > maxBytes) {
            throw new IllegalStateException("配置错误：liveproof.materials.srt-max-bytes 取值非法");
        }
        if (maxCount <= 2) {
            throw new IllegalStateException("配置错误：liveproof.materials.max-count 必须大于内置素材数 2");
        }
        Path dir = root();
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new IllegalStateException("配置错误：素材目录无法创建 " + dir, e);
        }
        log.info("素材目录就绪：{}（单文件上限 {} MB，素材上限 {}）",
                dir, maxBytes / 1024 / 1024, maxCount);
    }
}
