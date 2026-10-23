package com.liveproof.service;

import com.liveproof.dto.SubtitleCue;
import com.liveproof.exception.PolyvErrors;
import com.liveproof.polyv.PolyvSubtitleSnapshot;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 云端字幕归一化与严格校验。
 *
 * <p>保利威返回的时间是<b>毫秒</b>（`start` / `end`）。这里换算成秒并保留 3 位小数，
 * <b>不是</b>把毫秒当秒——那是阶段一明确记录的坑。
 *
 * <p>校验项（任一失败即 422 POLYV_SUBTITLE_INVALID，不做"跳过坏数据继续"的容错，
 * 否则会把上游故障伪装成正常字幕）：
 * <ol>
 *   <li>字幕非空，条数不超过配置上限</li>
 *   <li>单条：文本非空、start ≥ 0、end &gt; start</li>
 *   <li>顺序：start 不早于上一条 start，且不与上一条重叠（容差 {@link #OVERLAP_TOLERANCE_SEC}）</li>
 *   <li>时长：end 不超过视频时长 + {@link #TAIL_TOLERANCE_SEC}</li>
 * </ol>
 */
@Component
public class SmartSubtitleNormalizer {

    /** 与上一条的重叠容差（秒）：语音识别在边界处偶有几十毫秒交叠，超过即判异常。 */
    static final double OVERLAP_TOLERANCE_SEC = 0.05;

    /**
     * 尾部容差（秒）：配置里的视频时长常被截断成整数（106 vs 实际 106.47），
     * 因此允许字幕结束时间略微超出，超过 1 秒仍判越界。
     */
    static final double TAIL_TOLERANCE_SEC = 1.0;

    /** 毫秒 -> 秒（3 位小数）。 */
    public static double toSeconds(long millis) {
        return BigDecimal.valueOf(millis, 3)
                .setScale(3, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * @param raw         云端原始字幕（毫秒）
     * @param durationSec 视频时长基准（秒）；null 表示未配置，此时跳过越界校验
     * @return 序号从 1 重排的字幕（秒）
     */
    public List<SubtitleCue> normalize(List<PolyvSubtitleSnapshot.PolyvCue> raw, Double durationSec) {
        if (raw == null || raw.isEmpty()) {
            throw PolyvErrors.invalidSubtitles("字幕为空");
        }
        List<SubtitleCue> cues = new ArrayList<>(raw.size());
        double previousStart = -1;
        double previousEnd = -1;
        for (int i = 0; i < raw.size(); i++) {
            int position = i + 1;
            PolyvSubtitleSnapshot.PolyvCue item = raw.get(i);
            if (item == null) {
                throw PolyvErrors.invalidSubtitles("第 " + position + " 条为空");
            }
            double start = toSeconds(item.startMs());
            double end = toSeconds(item.endMs());
            String text = item.content() == null ? "" : item.content().trim();

            if (text.isEmpty()) {
                throw PolyvErrors.invalidSubtitles("第 " + position + " 条文本为空");
            }
            if (start < 0 || end < 0) {
                throw PolyvErrors.invalidSubtitles("第 " + position + " 条含负时间");
            }
            if (end <= start) {
                throw PolyvErrors.invalidSubtitles(
                        "第 " + position + " 条区间非法（start=" + start + "s, end=" + end + "s）");
            }
            if (start < previousStart) {
                throw PolyvErrors.invalidSubtitles(
                        "第 " + position + " 条时间顺序错乱（start=" + start + "s 早于上一条 " + previousStart + "s）");
            }
            if (i > 0 && start < previousEnd - OVERLAP_TOLERANCE_SEC) {
                throw PolyvErrors.invalidSubtitles(
                        "第 " + position + " 条与上一条重叠（start=" + start + "s, 上一条 end=" + previousEnd + "s）");
            }
            if (durationSec != null && end > durationSec + TAIL_TOLERANCE_SEC) {
                throw PolyvErrors.invalidSubtitles(
                        "第 " + position + " 条结束时间 " + end + "s 超出视频时长 " + durationSec + "s");
            }
            cues.add(new SubtitleCue(position, start, end, text));
            previousStart = start;
            previousEnd = end;
        }
        return cues;
    }
}
