package com.liveproof.service;

import com.liveproof.dto.SubtitleCue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时间区间重叠判定。
 *
 * <p>之所以单独测这个工具：云端 29 条 vs 本地 12 条的粒度差异是实测事实，
 * 一旦退回"完整包含"口径，跨边界的高亮与证据定位就会静默缺句——不会报错，只是结果错。
 */
class SubtitleWindowTest {

    private static final List<SubtitleCue> CUES = List.of(
            new SubtitleCue(1, 10.0, 20.0, "甲"),
            new SubtitleCue(2, 20.0, 30.0, "乙"),
            new SubtitleCue(3, 35.0, 40.0, "丙"));

    @Test
    @DisplayName("边界相接算重叠")
    void touchingBoundariesOverlap() {
        assertThat(SubtitleWindow.overlaps(10.0, 20.0, 20.0, 30.0)).isTrue();
        assertThat(SubtitleWindow.overlaps(10.0, 20.0, 0.0, 10.0)).isTrue();
    }

    @Test
    @DisplayName("完全不相交则不算重叠")
    void disjointDoesNotOverlap() {
        assertThat(SubtitleWindow.overlaps(10.0, 20.0, 20.1, 30.0)).isFalse();
        assertThat(SubtitleWindow.overlaps(10.0, 20.0, 0.0, 9.9)).isFalse();
    }

    @Test
    @DisplayName("窗口只覆盖 cue 的一小部分也需要返回")
    void partiallyCoveredCueIsKept() {
        assertThat(SubtitleWindow.overlapping(CUES, 19.5, 20.5))
                .extracting(SubtitleCue::index)
                .containsExactly(1, 2);
    }

    @Test
    @DisplayName("单边不限：只有 from 或只有 to 都成立")
    void openEndedWindows() {
        assertThat(SubtitleWindow.overlapping(CUES, 25.0, null))
                .extracting(SubtitleCue::index)
                .containsExactly(2, 3);
        assertThat(SubtitleWindow.overlapping(CUES, null, 19.0))
                .extracting(SubtitleCue::index)
                .containsExactly(1);
    }

    @Test
    @DisplayName("不限窗口原样返回全部")
    void noWindowReturnsAll() {
        assertThat(SubtitleWindow.overlapping(CUES, null, null)).hasSize(3);
        assertThat(SubtitleWindow.overlapping(List.of(), null, null)).isEmpty();
        assertThat(SubtitleWindow.overlapping(null, 0.0, 10.0)).isEmpty();
    }

    @Test
    @DisplayName("落在间隙里的窗口返回空，不硬凑最近的 cue")
    void gapReturnsEmpty() {
        assertThat(SubtitleWindow.overlapping(CUES, 31.0, 34.0)).isEmpty();
    }
}
