package com.liveproof.service;

import com.liveproof.dto.SubtitleCue;

import java.util.List;

/**
 * 时间区间工具。
 *
 * <p><b>为什么必须用重叠而不是包含</b>：本地人工 SRT 是 12 条，云端智能字幕是 29 条，
 * 一条本地字幕常被拆成 2–3 条云端 cue（阶段一实测）。若按「完整包含」筛选，
 * 跨越窗口边界的 cue 会被整条丢掉，导致高亮缺句、证据定位错位。
 *
 * <p>因此字幕关联与证据定位统一采用区间重叠判定：
 * {@code cue.startSec <= to && cue.endSec >= from}，两端均可为 null（表示不限）。
 * 边界相接（{@code cue.endSec == from}）视为重叠，避免边界抖动造成闪烁。
 */
public final class SubtitleWindow {

    private SubtitleWindow() {
    }

    /** 两个闭区间是否重叠；任一端为 null 表示该侧不设限。 */
    public static boolean overlaps(double cueStart, double cueEnd, Double from, Double to) {
        if (to != null && cueStart > to) {
            return false;
        }
        return from == null || cueEnd >= from;
    }

    /** 按重叠口径筛选；from / to 同时为 null 时原样返回。 */
    public static List<SubtitleCue> overlapping(List<SubtitleCue> cues, Double from, Double to) {
        if (cues == null || cues.isEmpty()) {
            return List.of();
        }
        if (from == null && to == null) {
            return List.copyOf(cues);
        }
        return cues.stream()
                .filter(cue -> overlaps(cue.startSec(), cue.endSec(), from, to))
                .toList();
    }
}
