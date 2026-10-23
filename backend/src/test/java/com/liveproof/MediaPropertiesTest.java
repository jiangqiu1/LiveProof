package com.liveproof;

import com.liveproof.config.MediaProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 媒体白名单配置校验测试。
 *
 * <p>白名单必须显式枚举。任何通配符、目录或路径片段都必须让应用启动失败，
 * 防止被改成"通用静态目录"从而误暴露带第三方水印的素材。
 */
class MediaPropertiesTest {

    private static MediaProperties props(List<String> files, List<String> subtitles,
                                         Map<String, String> mapping) {
        MediaProperties p = new MediaProperties();
        p.setRoot("../liveproof-demo/owned-demo");
        p.setAllowedFiles(files);
        p.setAllowedSubtitles(subtitles);
        p.setSubtitleMapping(mapping);
        return p;
    }

    @Test
    @DisplayName("合法显式枚举可正常初始化")
    void acceptsExplicitList() {
        MediaProperties p = props(
                List.of("candidateflow-demo.mp4", "candidateflow-demo-nosub.mp4"),
                List.of("candidateflow-demo.srt"),
                Map.of("candidateflow-demo.mp4", "candidateflow-demo.srt"));
        p.init();
        assertThat(p.resolveVideo("candidateflow-demo.mp4")).isPresent();
    }

    @Test
    @DisplayName("通配符被拒绝")
    void rejectsWildcard() {
        MediaProperties p = props(List.of("*.mp4"), List.of("candidateflow-demo.srt"), Map.of());
        assertThatThrownBy(p::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-files");
    }

    @Test
    @DisplayName("目录路径被拒绝")
    void rejectsDirectoryPath() {
        MediaProperties p = props(List.of("capture/video_silent.mp4"),
                List.of("candidateflow-demo.srt"), Map.of());
        assertThatThrownBy(p::init).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("空白名单被拒绝（必须是显式枚举）")
    void rejectsEmptyList() {
        MediaProperties p = props(List.of(), List.of("candidateflow-demo.srt"), Map.of());
        assertThatThrownBy(p::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("显式枚举");
    }

    @Test
    @DisplayName("映射引用了白名单外的文件被拒绝")
    void rejectsMappingOutsideWhitelist() {
        MediaProperties p = props(
                List.of("candidateflow-demo.mp4"),
                List.of("candidateflow-demo.srt"),
                Map.of("demo-video.mp4", "candidateflow-demo.srt"));
        assertThatThrownBy(p::init).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("白名单外文件名一律解析为空")
    void unknownNameResolvesEmpty() {
        MediaProperties p = props(List.of("candidateflow-demo.mp4"),
                List.of("candidateflow-demo.srt"),
                Map.of("candidateflow-demo.mp4", "candidateflow-demo.srt"));
        p.init();
        assertThat(p.resolveVideo("demo-video.mp4")).isEmpty();
        assertThat(p.resolveVideo("../../etc/passwd")).isEmpty();
        assertThat(p.resolveSubtitle("demo-video.srt")).isEmpty();
        assertThat(p.subtitleNameOf("demo-video.mp4")).isEmpty();
    }
}
