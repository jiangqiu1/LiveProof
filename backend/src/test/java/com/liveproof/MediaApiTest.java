package com.liveproof;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 媒体与字幕接口测试。
 *
 * <p>红线：只有显式白名单内的文件可达；带第三方水印的 demo-video.mp4、
 * capture/ 中间产物、任何路径穿越写法必须失败，且不泄露文件系统路径。
 */
class MediaApiTest extends ApiTestBase {

    private static final Path DEMO = Path.of("../liveproof-demo/owned-demo/candidateflow-demo.mp4");
    private static final Path NOSUB = Path.of("../liveproof-demo/owned-demo/candidateflow-demo-nosub.mp4");

    private static long sizeOf(Path path) throws Exception {
        assertThat(Files.isRegularFile(path)).as("素材必须存在：" + path).isTrue();
        return Files.size(path);
    }

    @Test
    @DisplayName("无 Range：200 全量 + Accept-Ranges + video/mp4")
    void fullResponse() throws Exception {
        long total = sizeOf(DEMO);
        mockMvc.perform(get("/media/candidateflow-demo.mp4"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("video/mp4")))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(total)));
    }

    @Test
    @DisplayName("单段 Range：206 + 正确 Content-Range 与 Content-Length")
    void singleRange() throws Exception {
        long total = sizeOf(DEMO);
        MockHttpServletResponse response = mockMvc.perform(get("/media/candidateflow-demo.mp4")
                        .header(HttpHeaders.RANGE, "bytes=100-1123"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 100-1123/" + total))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "1024"))
                .andReturn().getResponse();

        assertThat(response.getContentAsByteArray()).hasSize(1024);
    }

    @Test
    @DisplayName("尾部 Range：bytes=-500 返回最后 500 字节")
    void suffixRange() throws Exception {
        long total = sizeOf(DEMO);
        MockHttpServletResponse response = mockMvc.perform(get("/media/candidateflow-demo.mp4")
                        .header(HttpHeaders.RANGE, "bytes=-500"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE,
                        "bytes " + (total - 500) + "-" + (total - 1) + "/" + total))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "500"))
                .andReturn().getResponse();

        assertThat(response.getContentAsByteArray()).hasSize(500);
    }

    @Test
    @DisplayName("开放区间 Range：bytes=1000- 返回到文件末尾")
    void openEndedRange() throws Exception {
        long total = sizeOf(DEMO);
        MockHttpServletResponse response = mockMvc.perform(get("/media/candidateflow-demo.mp4")
                        .header(HttpHeaders.RANGE, "bytes=1000-"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE,
                        "bytes 1000-" + (total - 1) + "/" + total))
                .andReturn().getResponse();

        assertThat(response.getContentAsByteArray()).hasSize((int) (total - 1000));
    }

    @Test
    @DisplayName("越界 Range：416 + Content-Range: bytes */total")
    void rangeBeyondEnd() throws Exception {
        long total = sizeOf(DEMO);
        mockMvc.perform(get("/media/candidateflow-demo.mp4")
                        .header(HttpHeaders.RANGE, "bytes=99999999-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */" + total))
                .andExpect(jsonPath("$.error.code", org.hamcrest.Matchers.is("RANGE_NOT_SATISFIABLE")));
    }

    @Test
    @DisplayName("非法 Range 头：416")
    void invalidRangeHeader() throws Exception {
        mockMvc.perform(get("/media/candidateflow-demo.mp4")
                        .header(HttpHeaders.RANGE, "bytes=abc-def"))
                .andExpect(status().isRequestedRangeNotSatisfiable());
    }

    @Test
    @DisplayName("无字幕版在白名单内，可播放与分段请求")
    void nosubIsAllowed() throws Exception {
        long total = sizeOf(NOSUB);
        mockMvc.perform(get("/media/candidateflow-demo-nosub.mp4"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(total)));
        mockMvc.perform(get("/media/candidateflow-demo-nosub.mp4")
                        .header(HttpHeaders.RANGE, "bytes=0-1023"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-1023/" + total));
    }

    @Test
    @DisplayName("带第三方水印的 demo-video.mp4 永不放行")
    void watermarkedVideoDenied() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/media/demo-video.mp4"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", org.hamcrest.Matchers.is("MEDIA_NOT_FOUND")))
                .andReturn().getResponse();

        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("owned-demo").doesNotContain(":\\").doesNotContain("liveproof-demo");
    }

    @Test
    @DisplayName("capture/ 中间产物不可访问")
    void captureArtifactsDenied() throws Exception {
        mockMvc.perform(get("/media/video_silent.mp4"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/media/capture/video_silent.mp4"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("路径穿越写法一律失败")
    void pathTraversalDenied() throws Exception {
        for (String name : new String[]{
                "..%2Fapplication.yml",
                "%2e%2e%2fapplication.yml",
                "..%5Capplication.yml",
                "candidateflow-demo.mp4%00.txt"}) {
            int status = mockMvc.perform(get("/media/" + name))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as("穿越写法 " + name).isBetween(400, 404);
        }
    }

    @Test
    @DisplayName("白名单大小写敏感，覆盖式文件名无效")
    void whitelistCaseSensitive() throws Exception {
        mockMvc.perform(get("/media/CANDIDATEFLOW-DEMO.MP4"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/subtitles 返回解析后的 cue，时间为秒")
    void subtitles() throws Exception {
        String body = mockMvc.perform(get("/api/subtitles").param("video", "candidateflow-demo.mp4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.video", org.hamcrest.Matchers.is("candidateflow-demo.mp4")))
                .andExpect(jsonPath("$.subtitleFile", org.hamcrest.Matchers.is("candidateflow-demo.srt")))
                .andExpect(jsonPath("$.cueCount", org.hamcrest.Matchers.greaterThan(10)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        var node = objectMapper.readTree(body);
        var first = node.get("cues").get(0);
        assertThat(first.get("index").asInt()).isEqualTo(1);
        assertThat(first.get("startSec").asDouble()).isEqualTo(0.481);
        assertThat(first.get("endSec").asDouble()).isEqualTo(10.061);
        assertThat(first.get("text").asText()).contains("CandidateFlow");
    }

    @Test
    @DisplayName("非白名单视频无可取字幕")
    void subtitlesForDeniedVideo() throws Exception {
        mockMvc.perform(get("/api/subtitles").param("video", "demo-video.mp4"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", org.hamcrest.Matchers.is("SUBTITLE_NOT_FOUND")));
    }

    @Test
    @DisplayName("缺少 video 参数返回 400")
    void subtitlesWithoutParam() throws Exception {
        mockMvc.perform(get("/api/subtitles"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", org.hamcrest.Matchers.is("MISSING_PARAMETER")));
    }
}
