package com.liveproof.service;

import com.liveproof.dto.SubtitleCue;
import com.liveproof.exception.ApiException;
import com.liveproof.polyv.PolyvSubtitleSnapshot.PolyvCue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 云端字幕归一化测试：毫秒 -> 秒，以及严格校验。
 *
 * <p>重点防的两个坑：
 * <ul>
 *   <li>把毫秒当秒（阶段一报告明确记录过）</li>
 *   <li>遇到坏数据"跳过继续"，把上游故障伪装成正常字幕</li>
 * </ul>
 */
class SmartSubtitleNormalizerTest {

    private static final double DURATION = 106.47;

    private final SmartSubtitleNormalizer normalizer = new SmartSubtitleNormalizer();

    private static List<PolyvCue> cues(long[]... ranges) {
        return java.util.Arrays.stream(ranges)
                .map(range -> new PolyvCue(range[0], range[1], "文本"))
                .toList();
    }

    private static String code(Throwable error) {
        return ((ApiException) error).getCode();
    }

    @Test
    @DisplayName("毫秒转秒：680 -> 0.680，100900 -> 100.900")
    void convertsMillisecondsToSeconds() {
        assertThat(SmartSubtitleNormalizer.toSeconds(680)).isEqualTo(0.68);
        assertThat(SmartSubtitleNormalizer.toSeconds(1240)).isEqualTo(1.24);
        assertThat(SmartSubtitleNormalizer.toSeconds(100900)).isEqualTo(100.9);
        assertThat(SmartSubtitleNormalizer.toSeconds(47010)).isEqualTo(47.01);
        // 1ms 精度保留到 3 位小数，不出现浮点尾巴
        assertThat(SmartSubtitleNormalizer.toSeconds(1235)).isEqualTo(1.235);
    }

    @Test
    @DisplayName("序号从 1 重排，文本去空白")
    void renumbersAndTrims() {
        List<SubtitleCue> result = normalizer.normalize(
                List.of(new PolyvCue(680, 1240, "  大家好 "),
                        new PolyvCue(1480, 4800, "这里是产品演示")),
                DURATION);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).index()).isEqualTo(1);
        assertThat(result.get(1).index()).isEqualTo(2);
        assertThat(result.get(0).startSec()).isEqualTo(0.68);
        assertThat(result.get(0).endSec()).isEqualTo(1.24);
        assertThat(result.get(0).text()).isEqualTo("大家好");
    }

    @Test
    @DisplayName("空字幕列表直接 422，不返回空数组冒充成功")
    void rejectsEmptyList() {
        assertThatThrownBy(() -> normalizer.normalize(List.of(), DURATION))
                .satisfies(e -> assertThat(code(e)).isEqualTo("POLYV_SUBTITLE_INVALID"));
    }

    @Test
    @DisplayName("文本为空 -> 422")
    void rejectsBlankText() {
        assertThatThrownBy(() -> normalizer.normalize(List.of(new PolyvCue(0, 1000, "   ")), DURATION))
                .satisfies(e -> assertThat(code(e)).isEqualTo("POLYV_SUBTITLE_INVALID"));
    }

    @Test
    @DisplayName("end <= start -> 422")
    void rejectsNonPositiveDuration() {
        assertThatThrownBy(() -> normalizer.normalize(List.of(new PolyvCue(2000, 2000, "a")), DURATION))
                .satisfies(e -> assertThat(code(e)).isEqualTo("POLYV_SUBTITLE_INVALID"));
        assertThatThrownBy(() -> normalizer.normalize(List.of(new PolyvCue(3000, 2000, "a")), DURATION))
                .satisfies(e -> assertThat(code(e)).isEqualTo("POLYV_SUBTITLE_INVALID"));
    }

    @Test
    @DisplayName("负时间 -> 422")
    void rejectsNegativeTiming() {
        assertThatThrownBy(() -> normalizer.normalize(List.of(new PolyvCue(-1, 1000, "a")), DURATION))
                .satisfies(e -> assertThat(code(e)).isEqualTo("POLYV_SUBTITLE_INVALID"));
    }

    @Test
    @DisplayName("时间顺序错乱 -> 422")
    void rejectsOutOfOrder() {
        assertThatThrownBy(() -> normalizer.normalize(
                cues(new long[]{5000, 6000}, new long[]{3000, 4000}), DURATION))
                .satisfies(e -> assertThat(code(e)).isEqualTo("POLYV_SUBTITLE_INVALID"));
    }

    @Test
    @DisplayName("明显重叠 -> 422；几十毫秒的交叠被容忍")
    void handlesOverlapTolerance() {
        assertThatThrownBy(() -> normalizer.normalize(
                cues(new long[]{0, 5000}, new long[]{4000, 6000}), DURATION))
                .satisfies(e -> assertThat(code(e)).isEqualTo("POLYV_SUBTITLE_INVALID"));

        // 30ms 交叠 < 50ms 容差，属于 ASR 正常抖动
        assertThat(normalizer.normalize(
                cues(new long[]{0, 5030}, new long[]{5000, 6000}), DURATION)).hasSize(2);
    }

    @Test
    @DisplayName("结束时间超出视频时长 + 容差 -> 422")
    void rejectsBeyondDuration() {
        assertThatThrownBy(() -> normalizer.normalize(
                cues(new long[]{100000, 110000}), DURATION))
                .satisfies(e -> assertThat(code(e)).isEqualTo("POLYV_SUBTITLE_INVALID"));
    }

    @Test
    @DisplayName("整数时长截断（106 vs 实际 106.47）不误判越界")
    void toleratesRoundedDuration() {
        // 结束于 106.9s：比整数基准 106 多 0.9s，在 1s 容差内
        assertThat(normalizer.normalize(cues(new long[]{100000, 106900}), 106.0)).hasSize(1);
    }

    @Test
    @DisplayName("未配置时长基准时跳过越界校验，但其余校验仍然生效")
    void skipsBoundCheckWithoutDuration() {
        assertThat(normalizer.normalize(cues(new long[]{100000, 200000}), null)).hasSize(1);
        assertThatThrownBy(() -> normalizer.normalize(
                cues(new long[]{100000, 200000}, new long[]{150000, 210000}), null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("POLYV_SUBTITLE_INVALID"));
    }

    @Test
    @DisplayName("重叠口径：边界相接视为重叠，且不丢 cue")
    void windowUsesOverlapNotContainment() {
        List<SubtitleCue> all = normalizer.normalize(
                cues(new long[]{0, 10000}, new long[]{10000, 20000}, new long[]{20000, 30000}),
                DURATION);

        // 只与第二条部分重叠的窗口，必须同时返回第一条与第二条
        assertThat(SubtitleWindow.overlapping(all, 5.0, 15.0))
                .extracting(SubtitleCue::index)
                .containsExactly(1, 2);
        assertThat(SubtitleWindow.overlapping(all, 10.0, 10.0))
                .extracting(SubtitleCue::index)
                .containsExactly(1, 2);
    }
}
