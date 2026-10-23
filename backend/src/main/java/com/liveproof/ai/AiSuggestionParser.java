package com.liveproof.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liveproof.dto.AiEvidenceItem;
import com.liveproof.dto.AiSuggestion;
import com.liveproof.dto.SubtitleCue;
import com.liveproof.enums.AnswerStatus;
import com.liveproof.enums.EvidenceSufficiency;
import com.liveproof.enums.EvidenceType;
import com.liveproof.enums.EnumSupport;
import com.liveproof.exception.AiErrors;
import com.liveproof.service.EvidenceContextService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * 模型输出的结构化解析与安全规范化。
 *
 * <p>这是"模型原文"与"业务数据"之间的唯一闸门：
 * <ul>
 *   <li>非法枚举 → 直接拒绝（不做猜测性映射）</li>
 *   <li>越界或无法对应到真实字幕/关键帧的时间点 → 丢弃并记录说明</li>
 *   <li>结构非法 / 缺少核心字段 → 拒绝，不返回半成品的"看起来像结果"</li>
 * </ul>
 *
 * <p>被丢弃的内容不会静默消失，而是通过 {@code notes} 返回给用户。
 */
@Component
public class AiSuggestionParser {

    /** 时间点与材料容差（秒）。 */
    private static final double TOLERANCE = 2.0;

    private static final int MAX_EVIDENCE_ITEMS = 5;
    private static final int MAX_TIME_POINTS = 6;
    private static final int MAX_QUESTIONS = 5;
    private static final int MAX_TEXT = 1200;

    /** 模型漏写说明字段时的兜底文案：明确告诉用户「这是缺的」，不留空白让人误读。 */
    private static final String MISSING_LIMITATIONS = "（模型未说明局限，需人工补充）";
    private static final String MISSING_SUPPORTS = "（模型未说明该片段能支持什么结论）";
    private static final String MISSING_CANNOT_PROVE = "（模型未说明该片段不能证明什么）";

    private final ObjectMapper mapper;

    public AiSuggestionParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record ParseResult(AiSuggestion suggestion, List<String> notes) {
    }

    public ParseResult parse(String rawContent, EvidenceContextService.Prepared prepared,
                             BigDecimal windowStart, BigDecimal windowEnd) {
        JsonNode root = readObject(rawContent);

        String summary = text(root, "summary");
        if (summary == null) {
            throw AiErrors.invalidResponse("缺少 summary 字段");
        }
        AnswerStatus answerStatus = enumValue(AnswerStatus.class, root, "answerStatus");
        EvidenceType evidenceType = enumValue(EvidenceType.class, root, "evidenceType");
        EvidenceSufficiency sufficiency = enumValue(EvidenceSufficiency.class, root, "evidenceSufficiency");
        if (answerStatus == null || evidenceType == null || sufficiency == null) {
            throw AiErrors.invalidResponse("缺少 answerStatus / evidenceType / evidenceSufficiency 之一");
        }

        List<String> notes = new ArrayList<>();
        Double duration = prepared.durationSec();
        double upperBound = duration == null ? 7200 : duration + 0.5;

        List<AiEvidenceItem> items = parseEvidenceItems(root.path("evidenceItems"), prepared,
                windowStart, windowEnd, upperBound, notes);
        List<BigDecimal> timePoints = parseTimePoints(root.path("suggestedTimePoints"), prepared,
                upperBound, notes);
        List<String> questions = parseQuestions(root.path("unresolvedQuestions"));

        AiSuggestion suggestion = new AiSuggestion(
                summary,
                answerStatus,
                evidenceType,
                sufficiency,
                orDefault(text(root, "reasoning"), "模型未给出判断依据"),
                orDefault(text(root, "limitations"), MISSING_LIMITATIONS),
                items,
                timePoints,
                questions);
        if (text(root, "limitations") == null) {
            notes.add("模型没有写 limitations（局限与风险），该项已标记为需人工补充");
        }
        return new ParseResult(suggestion, notes);
    }

    // ---------- 结构化字段 ----------

    private JsonNode readObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw AiErrors.invalidResponse("模型返回空内容");
        }
        String content = raw.trim();
        if (content.startsWith("```")) {
            // 容忍 ```json ... ``` 包裹
            int firstBreak = content.indexOf('\n');
            content = firstBreak >= 0 ? content.substring(firstBreak + 1) : content;
            int fence = content.lastIndexOf("```");
            if (fence >= 0) {
                content = content.substring(0, fence);
            }
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw AiErrors.invalidResponse("模型返回的内容不是 JSON 对象");
        }
        try {
            JsonNode node = mapper.readTree(content.substring(start, end + 1));
            if (node == null || !node.isObject()) {
                throw AiErrors.invalidResponse("模型返回的 JSON 不是对象");
            }
            return node;
        } catch (com.liveproof.exception.ApiException e) {
            throw e;
        } catch (Exception e) {
            throw AiErrors.invalidResponse("模型返回的 JSON 无法解析");
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText("").trim();
        if (value.isEmpty()) {
            return null;
        }
        return value.length() > MAX_TEXT ? value.substring(0, MAX_TEXT) + "…" : value;
    }

    private static <E extends Enum<E> & com.liveproof.enums.ValueEnum> E enumValue(
            Class<E> type, JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String raw = node.asText("").trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return EnumSupport.parse(type, raw, field);
        } catch (IllegalArgumentException e) {
            throw AiErrors.invalidResponse("模型返回了非法的 " + field + " 取值：" + raw);
        }
    }

    private List<AiEvidenceItem> parseEvidenceItems(JsonNode array, EvidenceContextService.Prepared prepared,
                                                    BigDecimal windowStart, BigDecimal windowEnd,
                                                    double upperBound, List<String> notes) {
        if (!array.isArray()) {
            return List.of();
        }
        List<AiEvidenceItem> items = new ArrayList<>();
        int incomplete = 0;
        for (JsonNode node : array) {
            if (items.size() >= MAX_EVIDENCE_ITEMS) {
                notes.add("模型给出的候选证据超过 " + MAX_EVIDENCE_ITEMS + " 条，仅保留前 "
                        + MAX_EVIDENCE_ITEMS + " 条");
                break;
            }
            Double start = number(node.path("startSec"));
            Double end = number(node.path("endSec"));
            if (start == null || end == null) {
                notes.add("有一条候选证据缺少有效时间区间，已忽略");
                continue;
            }
            if (start < 0 || end < start || end > upperBound) {
                notes.add("候选证据时间区间 " + fmt(start) + "-" + fmt(end) + " 越界，已忽略");
                continue;
            }
            if (!matchesMaterial(start, end, prepared)) {
                notes.add("候选证据区间 " + fmt(start) + "-" + fmt(end)
                        + " 未对应任何已提供的字幕或关键帧，已忽略");
                continue;
            }
            double mid = (start + end) / 2;
            boolean outside = isOutside(mid, windowStart, windowEnd);
            String supports = trimOrNull(node.path("supports").asText(null));
            String cannotProve = trimOrNull(node.path("cannotProve").asText(null));
            if (supports == null || cannotProve == null) {
                incomplete++;
            }
            items.add(new AiEvidenceItem(
                    round(start), round(end),
                    trimOrNull(node.path("subtitle").asText(null)),
                    trimOrNull(node.path("observation").asText(null)),
                    orDefault(supports, MISSING_SUPPORTS),
                    orDefault(cannotProve, MISSING_CANNOT_PROVE),
                    outside,
                    alignedFrame(number(node.path("frameSec")), prepared)));
        }
        if (incomplete > 0) {
            notes.add("有 " + incomplete + " 条候选证据缺少说明字段（能支持什么 / 不能证明什么），已按「模型未说明」标注，请不要当作已核对过的结论");
        }
        items.sort(Comparator.comparing(AiEvidenceItem::startSec));
        return items;
    }

    private List<BigDecimal> parseTimePoints(JsonNode array, EvidenceContextService.Prepared prepared,
                                             double upperBound, List<String> notes) {
        if (!array.isArray()) {
            return List.of();
        }
        TreeSet<BigDecimal> points = new TreeSet<>();
        int dropped = 0;
        for (JsonNode node : array) {
            Double value = number(node);
            if (value == null) {
                dropped++;
                continue;
            }
            if (value < 0 || value > upperBound) {
                dropped++;
                continue;
            }
            if (!nearEvidence(value, prepared)) {
                dropped++;
                continue;
            }
            points.add(round(value));
        }
        if (dropped > 0) {
            notes.add("模型给出的 " + dropped + " 个时间点越界或无法对应到真实字幕/关键帧，已忽略");
        }
        if (points.size() > MAX_TIME_POINTS) {
            notes.add("建议时间点超过 " + MAX_TIME_POINTS + " 个，仅保留最早的 " + MAX_TIME_POINTS + " 个");
        }
        return points.stream().limit(MAX_TIME_POINTS).toList();
    }

    private static List<String> parseQuestions(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<String> questions = new ArrayList<>();
        for (JsonNode node : array) {
            if (questions.size() >= MAX_QUESTIONS) {
                break;
            }
            String value = node.asText("").trim();
            if (!value.isEmpty()) {
                questions.add(value.length() > 300 ? value.substring(0, 300) + "…" : value);
            }
        }
        return questions;
    }

    // ---------- 证据对齐 ----------

    /** 证据区间是否与已提供的字幕或关键帧时间对应（容差 {@link #TOLERANCE} 秒）。 */
    private static boolean matchesMaterial(double start, double end, EvidenceContextService.Prepared prepared) {
        for (SubtitleCue cue : prepared.cues()) {
            if (end >= cue.startSec() - TOLERANCE && start <= cue.endSec() + TOLERANCE) {
                return true;
            }
        }
        for (Double frame : prepared.frameTimes()) {
            if (start - TOLERANCE <= frame && end + TOLERANCE >= frame) {
                return true;
            }
        }
        return false;
    }

    private static boolean nearEvidence(double sec, EvidenceContextService.Prepared prepared) {
        for (SubtitleCue cue : prepared.cues()) {
            if (sec >= cue.startSec() - TOLERANCE && sec <= cue.endSec() + TOLERANCE) {
                return true;
            }
            if (Math.abs(sec - (cue.startSec() + cue.endSec()) / 2) <= TOLERANCE) {
                return true;
            }
        }
        for (Double frame : prepared.frameTimes()) {
            if (Math.abs(sec - frame) <= TOLERANCE) {
                return true;
            }
        }
        return false;
    }

    private static BigDecimal alignedFrame(Double value, EvidenceContextService.Prepared prepared) {
        if (value == null) {
            return null;
        }
        for (Double frame : prepared.frameTimes()) {
            if (Math.abs(value - frame) <= TOLERANCE) {
                return round(frame);
            }
        }
        return null;
    }

    private static boolean isOutside(double sec, BigDecimal start, BigDecimal end) {
        if (start != null && sec < start.doubleValue() - 0.5) {
            return true;
        }
        return end != null && sec > end.doubleValue() + 0.5;
    }

    // ---------- 小工具 ----------

    private static Double number(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        String raw = node.asText("").trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed.length() > MAX_TEXT ? trimmed.substring(0, MAX_TEXT) + "…" : trimmed;
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String fmt(double sec) {
        return AiPromptBuilder.fmt(sec);
    }
}
