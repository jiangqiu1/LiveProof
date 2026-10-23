package com.liveproof.controller;

import com.liveproof.exception.NotSatisfiableRangeException;
import com.liveproof.service.MediaService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.List;

/**
 * 媒体流接口。
 *
 * <p>白名单在 {@link MediaService} 内强制校验：只有显式枚举的文件名可达。
 * 带第三方水印的 demo-video.mp4 与 capture/ 中间产物不在白名单，固定 404。
 *
 * <p>Range 语义与旧实现对齐：
 * <ul>
 *   <li>无 Range → 200 + 全量 Content-Length</li>
 *   <li>单段 Range → 206 + Content-Range + 精确 Content-Length</li>
 *   <li>越界或非法 Range → 416 + Content-Range: bytes *&#47;total</li>
 *   <li>多段 Range → 按 RFC 9110 允许忽略，回落 200 全量</li>
 * </ul>
 *
 * <p>采用直接写 OutputStream + RandomAccessFile seek 的方式，
 * 不依赖消息转换器，避免 Content-Length 与转换器写法的耦合。
 */
@RestController
public class MediaController {

    private static final Logger log = LoggerFactory.getLogger(MediaController.class);

    private static final int BUFFER_SIZE = 64 * 1024;

    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @GetMapping("/media/{name}")
    public void media(@PathVariable("name") String name,
                      @RequestHeader(name = HttpHeaders.RANGE, required = false) String range,
                      HttpServletResponse response) throws IOException {

        MediaService.MediaFile file = mediaService.requireVideo(name);
        long total = file.size();

        long start = 0;
        long end = total - 1;
        boolean partial = false;

        if (range != null && !range.isBlank()) {
            List<HttpRange> ranges;
            try {
                ranges = HttpRange.parseRanges(range);
            } catch (IllegalArgumentException e) {
                throw new NotSatisfiableRangeException(total);
            }
            if (ranges.isEmpty()) {
                throw new NotSatisfiableRangeException(total);
            }
            if (ranges.size() == 1) {
                HttpRange httpRange = ranges.get(0);
                start = httpRange.getRangeStart(total);
                end = httpRange.getRangeEnd(total);
                if (start < 0 || start > end || start >= total) {
                    throw new NotSatisfiableRangeException(total);
                }
                partial = true;
            }
        }

        long count = end - start + 1;

        response.setStatus(partial ? HttpStatus.PARTIAL_CONTENT.value() : HttpStatus.OK.value());
        response.setContentType(file.contentType());
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (partial) {
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total);
        }
        response.setContentLengthLong(count);

        try (RandomAccessFile raf = new RandomAccessFile(file.path().toFile(), "r")) {
            raf.seek(start);
            OutputStream out = response.getOutputStream();
            long remaining = count;
            byte[] buffer = new byte[BUFFER_SIZE];
            while (remaining > 0) {
                int read = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
            out.flush();
        } catch (IOException e) {
            // 客户端提前断开（拖动进度条时常见）：响应已提交，不抛错也不改写状态码
            log.debug("媒体流中断：{}（{}）", name, e.getMessage());
        }
    }
}
