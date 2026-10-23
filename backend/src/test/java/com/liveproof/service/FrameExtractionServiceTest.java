package com.liveproof.service;

import com.liveproof.config.AiProperties;
import com.liveproof.config.MediaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 关键帧提取的真实运行测试。
 *
 * <p>需要本机可用 FFmpeg（环境变量 LIVEPROOF_FFMPEG_PATH 或 PATH）；缺失时整类跳过，
 * 不会伪造"已提取"的结论。
 */
class FrameExtractionServiceTest {

    private static final String DEMO = "candidateflow-demo.mp4";

    private MediaProperties mediaProperties;
    private FrameExtractionService service;

    @BeforeEach
    void setUp() {
        mediaProperties = new MediaProperties();
        mediaProperties.setRoot("../liveproof-demo/owned-demo");
        mediaProperties.setAllowedFiles(List.of(DEMO, "candidateflow-demo-nosub.mp4"));
        mediaProperties.setAllowedSubtitles(List.of("candidateflow-demo.srt"));
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put(DEMO, "candidateflow-demo.srt");
        mapping.put("candidateflow-demo-nosub.mp4", "candidateflow-demo.srt");
        mediaProperties.setSubtitleMapping(mapping);
        mediaProperties.init();

        AiProperties ai = new AiProperties();
        String configured = System.getenv("LIVEPROOF_FFMPEG_PATH");
        if (configured != null && !configured.isBlank()) {
            ai.setFfmpegPath(configured);
        }
        ai.setMaxFrames(3);
        ai.setFrameMaxWidth(720);
        ai.setFfmpegTimeoutMs(20000);
        service = new FrameExtractionService(mediaProperties, ai);
    }

    @Test
    @DisplayName("真实视频能读出时长（约 106 秒）")
    void readsDuration() {
        assumeTrue(service.isFfmpegAvailable(), "本机没有可用的 FFmpeg，跳过画面测试");
        Path video = mediaProperties.resolveVideo(DEMO).orElseThrow();
        Double duration = service.durationOf(video);
        assertThat(duration).isNotNull();
        assertThat(duration).isBetween(100.0, 115.0);
    }

    @Test
    @DisplayName("能按时间点提取真实 JPEG 帧，且宽度不超过上限")
    void extractsRealFrame() {
        assumeTrue(service.isFfmpegAvailable(), "本机没有可用的 FFmpeg，跳过画面测试");
        FrameExtractionService.Extraction extraction =
                service.extract(DEMO, List.of(22.66, 48.43));
        assertThat(extraction.available()).isTrue();
        assertThat(extraction.frames()).hasSize(2);
        for (FrameExtractionService.Frame frame : extraction.frames()) {
            assertThat(frame.jpeg().length).isGreaterThan(1000);
            assertThat(frame.jpeg()[0] & 0xFF).isEqualTo(0xFF);
            assertThat(frame.jpeg()[1] & 0xFF).isEqualTo(0xD8);
            assertThat(frame.width()).isPositive();
            assertThat(frame.width()).isLessThanOrEqualTo(720);
            assertThat(frame.height()).isPositive();
        }
    }

    @Test
    @DisplayName("白名单之外的视频不会被抽帧")
    void rejectsNonWhitelistedVideo() {
        FrameExtractionService.Extraction extraction =
                service.extract("demo-video.mp4", List.of(10.0));
        assertThat(extraction.available()).isFalse();
        assertThat(extraction.frames()).isEmpty();
        assertThat(extraction.note()).contains("白名单");
    }

    @Test
    @DisplayName("非法时间点被跳过，不会越界抽帧")
    void skipsOutOfRangeTimes() {
        assumeTrue(service.isFfmpegAvailable(), "本机没有可用的 FFmpeg，跳过画面测试");
        FrameExtractionService.Extraction extraction =
                service.extract(DEMO, List.of(-5.0, 9999.0));
        assertThat(extraction.available()).isFalse();
        assertThat(extraction.frames()).isEmpty();
    }

    @Test
    @DisplayName("FFmpeg 不可用时报错信息明确，不返回假画面")
    void reportsMissingFfmpeg() {
        AiProperties ai = new AiProperties();
        ai.setFfmpegPath("ffmpeg-definitely-not-installed");
        FrameExtractionService broken = new FrameExtractionService(mediaProperties, ai);
        assertThat(broken.isFfmpegAvailable()).isFalse();
        FrameExtractionService.Extraction extraction = broken.extract(DEMO, List.of(20.0));
        assertThat(extraction.available()).isFalse();
        assertThat(extraction.note()).contains("FFmpeg");
    }
}
