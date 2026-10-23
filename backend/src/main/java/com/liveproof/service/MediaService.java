package com.liveproof.service;

import com.liveproof.config.MediaProperties;
import com.liveproof.exception.NotFoundException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 媒体文件定位。白名单校验与目录逃逸防护在 {@link MediaProperties} 内完成。
 */
@Service
public class MediaService {

    /** 已通过白名单校验的媒体文件。 */
    public record MediaFile(Path path, long size, String contentType) {
    }

    private static final String MP4 = "video/mp4";

    private final MediaProperties mediaProperties;

    public MediaService(MediaProperties mediaProperties) {
        this.mediaProperties = mediaProperties;
    }

    public MediaFile requireVideo(String name) {
        Path path = mediaProperties.resolveVideo(name)
                .orElseThrow(() -> NotFoundException.mediaNotFound(name));
        try {
            return new MediaFile(path, Files.size(path), MP4);
        } catch (IOException e) {
            throw NotFoundException.mediaNotFound(name);
        }
    }
}
