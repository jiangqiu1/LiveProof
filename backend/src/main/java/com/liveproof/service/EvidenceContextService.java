package com.liveproof.service;

import com.liveproof.config.AiProperties;
import com.liveproof.config.MediaProperties;
import com.liveproof.dto.SubtitleCue;
import com.liveproof.dto.SubtitleResponse;
import com.liveproof.entity.MaterialEntity;
import com.liveproof.enums.SubtitleSource;
import com.liveproof.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 证据上下文构建：先用字幕定位候选区间，再推导少量关键帧时间点。
 *
 * <p>顺序不能颠倒：字幕是定位手段，画面是佐证手段。整段视频密集抽帧既慢又贵，
 * 也会让模型在没有定位的情况下"泛泛而谈"。
 */
@Service
public class EvidenceContextService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceContextService.class);

    /** 时间窗内最多提交的字幕条数。 */
    private static final int MAX_WINDOW_CUES = 12;

    /**
     * 准备好的分析上下文。
     *
     * @param source        本次定位所用的字幕来源
     * @param cues          选中的字幕（时间升序）
     * @param outside       其中位于原始时间区间之外的 cue index
     * @param frameTimes    候选关键帧时间点（来自字幕的中点）
     * @param truncated     是否因字符上限裁剪过字幕
     * @param subtitleNote  字幕不可用时的原因
     */
    public record Prepared(
            String source,
            String videoName,
            Double durationSec,
            String subtitleFile,
            int totalCues,
            List<SubtitleCue> cues,
            Set<Integer> outside,
            boolean truncated,
            List<Double> frameTimes,
            String subtitleNote
    ) {
    }

    private final SubtitleService subtitleService;
    private final SmartSubtitleService smartSubtitleService;
    private final MediaProperties mediaProperties;
    private final FrameExtractionService frameExtractionService;
    private final MaterialService materialService;
    private final AiProperties aiProperties;

    public EvidenceContextService(SubtitleService subtitleService,
                                  SmartSubtitleService smartSubtitleService,
                                  MediaProperties mediaProperties,
                                  FrameExtractionService frameExtractionService,
                                  MaterialService materialService,
                                  AiProperties aiProperties) {
        this.subtitleService = subtitleService;
        this.smartSubtitleService = smartSubtitleService;
        this.mediaProperties = mediaProperties;
        this.frameExtractionService = frameExtractionService;
        this.materialService = materialService;
        this.aiProperties = aiProperties;
    }

    /** 默认使用本地 SRT 字幕（与既有行为一致）。 */
    public Prepared build(String videoName, BigDecimal startSec, BigDecimal endSec,
                          String question, String claim) {
        return build(videoName, startSec, endSec, question, claim, SubtitleSource.LOCAL);
    }

    public Prepared build(String videoName, BigDecimal startSec, BigDecimal endSec,
                          String question, String claim, SubtitleSource source) {
        SubtitleSource effective = source == null ? SubtitleSource.LOCAL : source;
        SourceCues loaded = loadCues(videoName, effective);
        List<SubtitleCue> all = loaded.cues();
        String subtitleNote = all.isEmpty()
                ? (effective == SubtitleSource.CLOUD
                    ? "云端智能字幕没有返回可用字幕，本次未做字幕定位"
                    : "该视频没有可用的配套字幕，本次未做字幕定位")
                : null;

        double windowStart = startSec == null ? 0 : startSec.doubleValue();
        double windowEnd = endSec == null ? Double.MAX_VALUE : endSec.doubleValue();
        String keyword = ((question == null ? "" : question) + (claim == null ? "" : claim)).trim();

        List<SubtitleCue> windowCues = new ArrayList<>();
        for (SubtitleCue cue : all) {
            if (cue.endSec() >= windowStart - 0.5 && cue.startSec() <= windowEnd + 0.5) {
                windowCues.add(cue);
            }
        }
        // 时间窗内没有字幕时（例如区间落在静默段），退化为"距区间最近的 2 条"
        if (windowCues.isEmpty() && !all.isEmpty()) {
            windowCues.addAll(nearest(all, windowStart, 2));
        }

        List<SubtitleCue> extraCues = pickOutsideWindow(all, windowCues, keyword);

        List<SubtitleCue> selected = new ArrayList<>();
        Set<Integer> outside = new HashSet<>();
        selected.addAll(windowCues);
        for (SubtitleCue cue : extraCues) {
            selected.add(cue);
            outside.add(cue.index());
        }
        selected.sort((a, b) -> Double.compare(a.startSec(), b.startSec()));

        int windowLimit = Math.min(MAX_WINDOW_CUES, selected.size());
        if (selected.size() > windowLimit) {
            selected = new ArrayList<>(selected.subList(0, windowLimit));
            outside.retainAll(selected.stream().map(SubtitleCue::index).collect(Collectors.toSet()));
        }

        boolean truncated = false;
        int budget = Math.max(500, aiProperties.getMaxSubtitleChars());
        while (charCount(selected) > budget && !selected.isEmpty()) {
            // 先裁时间窗外的补充字幕，再裁窗口内最靠后的
            SubtitleCue drop = selected.stream().filter(c -> outside.contains(c.index()))
                    .reduce((a, b) -> b).orElse(selected.get(selected.size() - 1));
            selected.remove(drop);
            outside.remove(drop.index());
            truncated = true;
        }

        Double duration = duration(videoName);
        List<Double> frameTimes = pickFrameTimes(selected, outside, duration);

        // subtitleFile 只描述本地 SRT 文件名：云端来源下它不存在，不能张冠李戴
        String subtitleFile = effective == SubtitleSource.LOCAL ? loaded.subtitleFile() : null;
        return new Prepared(effective.getValue(), videoName, duration, subtitleFile, all.size(), selected, outside,
                truncated, frameTimes, subtitleNote);
    }

    /** 一次加载的结果：cue 列表 + 实际使用的本地字幕文件名（云端来源恒为 null）。 */
    private record SourceCues(List<SubtitleCue> cues, String subtitleFile) {
    }

    private SourceCues loadCues(String videoName, SubtitleSource source) {
        if (source == SubtitleSource.CLOUD) {
            // 云端字幕不可用时如实抛出（409），由上层告诉用户先准备任务；
            // 不用空字幕降级，否则「没准备好」会被说成「没有相关内容」
            return new SourceCues(smartSubtitleService.readyCuesFor(videoName, null, null).cues(), null);
        }
        try {
            SubtitleResponse response = subtitleService.forVideo(videoName);
            return new SourceCues(response.cues(), response.subtitleFile());
        } catch (ApiException e) {
            log.info("视频 {} 无可用字幕：{}", videoName, e.getMessage());
            return new SourceCues(List.of(), null);
        }
    }

    private Double duration(String videoName) {
        Optional<Path> path = mediaProperties.resolveVideo(videoName);
        if (path.isPresent()) {
            return path.map(frameExtractionService::durationOf).orElse(null);
        }
        // 云端素材：本地没有视频文件，用素材登记的时长做越界保护
        MaterialEntity material = materialService.findByRef(videoName);
        return material == null || material.getDurationSec() == null
                ? null : material.getDurationSec().doubleValue();
    }

    /** 时间窗外：按与问题/承诺的关键词重合度挑选，最多 maxExtraCues 条。 */
    private List<SubtitleCue> pickOutsideWindow(List<SubtitleCue> all, List<SubtitleCue> windowCues, String keyword) {
        if (keyword.isBlank()) {
            return List.of();
        }
        Set<Integer> windowIndexes = new HashSet<>();
        windowCues.forEach(cue -> windowIndexes.add(cue.index()));
        Set<String> target = bigrams(keyword);

        return all.stream()
                .filter(cue -> !windowIndexes.contains(cue.index()))
                .map(cue -> Map.entry(cue, overlap(bigrams(cue.text()), target)))
                .filter(entry -> entry.getValue() > 0)
                .sorted((a, b) -> {
                    int byScore = Integer.compare(b.getValue(), a.getValue());
                    return byScore != 0 ? byScore : Double.compare(a.getKey().startSec(), b.getKey().startSec());
                })
                .limit(Math.max(0, aiProperties.getMaxExtraCues()))
                .map(Map.Entry::getKey)
                .sorted((a, b) -> Double.compare(a.startSec(), b.startSec()))
                .toList();
    }

    /**
     * 关键帧时间点：优先窗口内字幕中点，均匀取样；不足时用窗口外补充字幕补齐。
     * 时间点一律 clamp 到 [0, 时长-0.1]，避免越界抽帧。
     */
    private List<Double> pickFrameTimes(List<SubtitleCue> selected, Set<Integer> outside, Double duration) {
        int limit = Math.max(1, aiProperties.getMaxFrames());
        List<SubtitleCue> inWindow = selected.stream().filter(c -> !outside.contains(c.index())).toList();
        List<SubtitleCue> outsideList = selected.stream().filter(c -> outside.contains(c.index())).toList();

        List<Double> times = new ArrayList<>();
        for (SubtitleCue cue : evenSample(inWindow, limit)) {
            addTime(times, (cue.startSec() + cue.endSec()) / 2, duration);
        }
        for (SubtitleCue cue : outsideList) {
            if (times.size() >= limit) {
                break;
            }
            addTime(times, (cue.startSec() + cue.endSec()) / 2, duration);
        }
        if (times.isEmpty() && !selected.isEmpty()) {
            addTime(times, (selected.get(0).startSec() + selected.get(0).endSec()) / 2, duration);
        }
        return times;
    }

    private static void addTime(List<Double> times, double raw, Double duration) {
        double max = duration == null ? Double.MAX_VALUE : Math.max(0, duration - 0.1);
        double sec = Math.min(Math.max(0, raw), max);
        sec = Math.round(sec * 100.0) / 100.0;
        for (Double existing : times) {
            if (Math.abs(existing - sec) < 0.5) {
                return;
            }
        }
        times.add(sec);
    }

    private static List<SubtitleCue> evenSample(List<SubtitleCue> cues, int limit) {
        if (cues.size() <= limit) {
            return cues;
        }
        List<SubtitleCue> sampled = new ArrayList<>();
        double step = (double) cues.size() / limit;
        for (int i = 0; i < limit; i++) {
            sampled.add(cues.get(Math.min(cues.size() - 1, (int) Math.floor(i * step))));
        }
        return new LinkedHashSet<>(sampled).stream().toList();
    }

    private static List<SubtitleCue> nearest(List<SubtitleCue> all, double anchor, int limit) {
        return all.stream()
                .sorted((a, b) -> Double.compare(distance(a, anchor), distance(b, anchor)))
                .limit(limit)
                .sorted((a, b) -> Double.compare(a.startSec(), b.startSec()))
                .toList();
    }

    private static double distance(SubtitleCue cue, double anchor) {
        if (anchor >= cue.startSec() && anchor <= cue.endSec()) {
            return 0;
        }
        return Math.min(Math.abs(cue.startSec() - anchor), Math.abs(cue.endSec() - anchor));
    }

    private static int charCount(List<SubtitleCue> cues) {
        return cues.stream().mapToInt(cue -> cue.text() == null ? 0 : cue.text().length()).sum();
    }

    /** 中文不用分词：用字符二元组求重合度，足够做候选定位且零依赖。 */
    static Set<String> bigrams(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String cleaned = text.replaceAll("[\\s\\p{Punct}，。：；！？、（）“”‘’《》]", "");
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 2 <= cleaned.length(); i++) {
            grams.add(cleaned.substring(i, i + 2));
        }
        return grams;
    }

    private static int overlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String gram : a) {
            if (b.contains(gram)) {
                count++;
            }
        }
        return count;
    }
}
