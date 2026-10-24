package com.liveproof.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liveproof.dto.AiCaseCandidate;
import com.liveproof.dto.CaseCreateRequest;
import com.liveproof.dto.SubtitleCue;
import com.liveproof.exception.AiErrors;
import com.liveproof.service.CaseImportService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 候选核验事项的结构化解析与安全规范化。
 *
 * <p>与 {@link AiSuggestionParser} 同样是「模型原文 → 业务数据」的唯一闸门，规则更严：
 * <ul>
 *   <li><b>时间必须落在真实字幕上</b>：与任何一条 cue 都不重叠的候选直接丢弃，
 *       不允许把编造的时间点带进待核验清单</li>
 *   <li><b>引用必须是原话</b>：{@code quote} 与覆盖到的字幕文本完全不重合时，
 *       视为模型改写或编造，替换为真实字幕并记一条说明，而不是原样展示</li>
 *   <li><b>去重是确定性的</b>：批内与库内都按归一后的问句比较，
 *       不依赖模型自称「这条和上面重复」</li>
 * </ul>
 *
 * <p>被丢弃 / 被改写的内容不会静默消失，全部通过 {@code notes} 返回。
 */
@Component
public class AiCandidateParser {

    /** 时间与字幕的容差（秒），与核验链路保持一致。 */
    private static final double TOLERANCE = 2.0;

    /** 单次最多返回的候选条数，比模型侧上限略宽，超出则截断。 */
    static final int MAX_CANDIDATES = 8;

    private static final int MAX_QUOTE = 200;

    private final ObjectMapper mapper;

    public AiCandidateParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param cues         全部可用字幕（时间升序）
     * @param durationSec  视频时长，null 表示未知（按 7200 秒兜底）
     * @param existingKeys 库内该素材已有事项的「归一问题 → case id」，用于去重
     */
    public record ParseResult(List<AiCaseCandidate> candidates, List<String> notes) {
    }

    public ParseResult parse(String rawContent, List<SubtitleCue> cues, Double durationSec,
                             Map<String, String> existingKeys) {
        JsonNode root = readObject(rawContent);
        JsonNode array = root.path("candidates");
        if (!array.isArray()) {
            throw AiErrors.invalidResponse("缺少 candidates 数组");
        }

        List<String> notes = new ArrayList<>();
        double upperBound = durationSec == null ? 7200 : durationSec + 0.5;

        List<AiCaseCandidate> candidates = new ArrayList<>();
        Map<String, Integer> batchKeys = new HashMap<>();
        int dropped = 0;
        int rewrittenQuote = 0;
        int overLimit = 0;

        for (JsonNode node : array) {
            if (candidates.size() >= MAX_CANDIDATES) {
                overLimit++;
                continue;
            }
            String question = trimToNull(node.path("question").asText(null));
            String claim = trimToNull(node.path("claim").asText(null));
            Double start = number(node.path("startSec"));
            Double end = number(node.path("endSec"));

            if (question == null || claim == null) {
                dropped++;
                continue;
            }
            if (question.length() > CaseCreateRequest.MAX_TITLE_LENGTH
                    || claim.length() > CaseCreateRequest.MAX_TITLE_LENGTH) {
                dropped++;
                continue;
            }
            if (start == null || end == null || start < 0 || end < start || end > upperBound) {
                dropped++;
                continue;
            }
            List<Integer> covered = coveredCues(start, end, cues);
            if (covered.isEmpty()) {
                dropped++;
                continue;
            }

            String realText = cueText(covered, cues);
            String quote = trimToNull(node.path("quote").asText(null));
            if (quote == null) {
                quote = realText;
                rewrittenQuote++;
            } else if (!sharesContent(quote, realText)) {
                // 模型把原话改写了：界面要展示可以回看核对的字幕，不能展示它改写后的句子
                quote = realText;
                rewrittenQuote++;
            }

            String key = CaseImportService.key(question);
            boolean duplicateInBatch = batchKeys.containsKey(key);
            boolean duplicateInDb = existingKeys.containsKey(key);

            candidates.add(new AiCaseCandidate(
                    "cand-" + (candidates.size() + 1),
                    question,
                    claim,
                    round(start),
                    round(end),
                    clip(quote),
                    covered,
                    duplicateInBatch || duplicateInDb,
                    duplicateInDb ? existingKeys.get(key) : null,
                    duplicateInBatch ? batchKeys.get(key) : null));
            if (!duplicateInBatch) {
                batchKeys.put(key, candidates.size());
            }
        }

        if (dropped > 0) {
            notes.add("模型给出的 " + dropped + " 条候选缺少问题/承诺，或时间区间越界、"
                    + "对不上任何一条字幕，已忽略");
        }
        if (rewrittenQuote > 0) {
            notes.add("有 " + rewrittenQuote + " 条候选的引用原文与字幕不一致（模型改写或未给出），"
                    + "已替换为该区间的真实字幕");
        }
        if (overLimit > 0) {
            notes.add("模型给出超过 " + MAX_CANDIDATES + " 条候选，仅保留前 " + MAX_CANDIDATES + " 条");
        }
        int duplicates = (int) candidates.stream().filter(AiCaseCandidate::duplicate).count();
        if (duplicates > 0) {
            notes.add("有 " + duplicates + " 条与已有事项或本批前面的候选重复，默认不勾选");
        }

        candidates.sort((a, b) -> a.startSec().compareTo(b.startSec()));
        return new ParseResult(candidates, notes);
    }

    // ------------------------------------------------------------------ 对齐与校验

    /**
     * 与 [start, end] 有重叠的 cue 序号（升序）。
     *
     * <p>先按<b>严格重叠</b>算，为空才退回 {@link #TOLERANCE} 秒容差：
     * 容差是为了容忍模型把边界写偏（如 25.29 写成 25.5），
     * 但它会让「刚结束的下一句」也被算进来，导致 quote 多拼一句无关文本。
     * 严格重叠已经命中时就不该引入容差。
     */
    private static List<Integer> coveredCues(double start, double end, List<SubtitleCue> cues) {
        List<Integer> strict = new ArrayList<>();
        for (SubtitleCue cue : cues) {
            if (end >= cue.startSec() && start <= cue.endSec()) {
                strict.add(cue.index());
            }
        }
        if (!strict.isEmpty()) {
            return strict;
        }
        List<Integer> loose = new ArrayList<>();
        for (SubtitleCue cue : cues) {
            if (end >= cue.startSec() - TOLERANCE && start <= cue.endSec() + TOLERANCE) {
                loose.add(cue.index());
            }
        }
        return loose;
    }

    private static String cueText(List<Integer> covered, List<SubtitleCue> cues) {
        Set<Integer> wanted = new HashSet<>(covered);
        StringBuilder sb = new StringBuilder();
        for (SubtitleCue cue : cues) {
            if (!wanted.contains(cue.index())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(cue.text().replace("\n", " ").trim());
            if (sb.length() > MAX_QUOTE) {
                break;
            }
        }
        return sb.toString();
    }

    /** 引用与真实字幕是否有实质重合：任一方向包含，或共享 ≥3 个字符二元组。 */
    private static boolean sharesContent(String quote, String realText) {
        String a = normalize(quote);
        String b = normalize(realText);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.contains(b) || b.contains(a)) {
            return true;
        }
        Set<String> grams = bigrams(b);
        int shared = 0;
        for (String gram : bigrams(a)) {
            if (grams.contains(gram)) {
                shared++;
                if (shared >= 3) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\s\\p{Punct}，。：；！？、（）“”‘’《》]", "");
    }

    private static Set<String> bigrams(String text) {
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 2 <= text.length(); i++) {
            grams.add(text.substring(i, i + 2));
        }
        return grams;
    }

    // ------------------------------------------------------------------ 读取

    private JsonNode readObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw AiErrors.invalidResponse("模型返回空内容");
        }
        String content = raw.trim();
        if (content.startsWith("```")) {
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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private static String clip(String text) {
        return text.length() > MAX_QUOTE ? text.substring(0, MAX_QUOTE) + "…" : text;
    }

    private static BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
