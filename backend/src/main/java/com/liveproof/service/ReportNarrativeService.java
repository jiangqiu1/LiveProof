package com.liveproof.service;

import com.liveproof.ai.AiClient;
import com.liveproof.ai.AiCompletion;
import com.liveproof.ai.AiPromptBuilder;
import com.liveproof.entity.CaseEntity;
import com.liveproof.entity.TodoEntity;
import com.liveproof.enums.AnswerStatus;
import com.liveproof.enums.EvidenceSufficiency;
import com.liveproof.enums.EvidenceType;
import com.liveproof.enums.ReviewStatus;
import com.liveproof.enums.TodoStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 报告里的 AI 归纳段落。
 *
 * <p>边界：
 * <ul>
 *   <li>只接收后端已经算好的事实摘要，模型<b>不参与</b>统计</li>
 *   <li>输出必须通过数字核对：句子中出现的事实数字若不在摘要里，整句丢弃并记说明，
 *       避免"编造统计"。这是可用性上的折中，但宁缺勿假</li>
 *   <li>模型失败不影响报告生成，只降级为"无 AI 归纳"</li>
 * </ul>
 */
@Component
public class ReportNarrativeService {

    private static final Logger log = LoggerFactory.getLogger(ReportNarrativeService.class);

    private final AiClient aiClient;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public ReportNarrativeService(AiClient aiClient, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.aiClient = aiClient;
        this.mapper = mapper;
    }

    /**
     * 归纳结果。
     *
     * @param used 是否成功产出可用的归纳
     */
    public record Narrative(boolean used, String overview, List<String> findings, List<String> risks,
                            List<String> notes) {
    }

    public static final Narrative NONE = new Narrative(false, null, List.of(), List.of(), List.of());

    public Narrative build(String datasetLabel, List<CaseEntity> cases, List<TodoEntity> todos,
                           Map<String, Integer> stats) {
        if (!aiClient.isConfigured()) {
            return new Narrative(false, null, List.of(), List.of(),
                    List.of("AI 归纳未启用：运行时未配置 DEEPSEEK_API_KEY，本报告仅包含可追溯事实"));
        }
        String digest = digest(datasetLabel, cases, todos, stats);
        try {
            AiCompletion completion = aiClient.complete(AiPromptBuilder.NARRATIVE_SYSTEM, digest, List.of(), true);
            return parse(completion.content(), allowedNumbers(cases, todos, stats));
        } catch (Exception e) {
            log.warn("报告 AI 归纳失败：{}", e.getMessage());
            return new Narrative(false, null, List.of(), List.of(),
                    List.of("AI 归纳调用失败（不影响报告事实部分）：" + e.getMessage()));
        }
    }

    /** 事实摘要：只喂结论性字段，不喂整段视频或整篇字幕。 */
    private String digest(String datasetLabel, List<CaseEntity> cases, List<TodoEntity> todos,
                          Map<String, Integer> stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("数据集：").append(datasetLabel).append('\n');
        sb.append("统计（已由服务端计算，禁止改写或新增）：\n");
        stats.forEach((key, value) -> sb.append("- ").append(key).append("：").append(value).append('\n'));
        sb.append("\n逐项已保存状态：\n");
        for (CaseEntity c : cases) {
            sb.append("- ").append(c.getId()).append("｜问题：").append(nullToDash(c.getQuestion()))
                    .append("｜核验进度：").append(labelOf(c.getReviewStatus()))
                    .append("｜回答状态：").append(labelOf(c.getAnswerStatus()))
                    .append("｜证据形态：").append(labelOf(c.getEvidenceType()))
                    .append("｜充分性：").append(labelOf(c.getEvidenceSufficiency()));
            if (c.getReasoning() != null && !c.getReasoning().isBlank()) {
                sb.append("｜依据：").append(trim(c.getReasoning(), 120));
            }
            if (c.getLimitations() != null && !c.getLimitations().isBlank()) {
                sb.append("｜局限：").append(trim(c.getLimitations(), 120));
            }
            sb.append('\n');
        }
        sb.append("\n待办：\n");
        if (todos.isEmpty()) {
            sb.append("- 无\n");
        } else {
            for (TodoEntity todo : todos) {
                sb.append("- ").append(todo.getTitle()).append("｜关联事项：").append(todo.getCaseId())
                        .append("｜状态：").append(labelOf(todo.getStatus())).append('\n');
            }
        }
        sb.append("\n请基于以上事实输出 JSON：{\"overview\":\"...\",\"findings\":[...],\"risks\":[...]}。");
        return sb.toString();
    }

    private Narrative parse(String content, Set<String> allowedNumbers) {
        String json = extractJson(content);
        try {
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            String rawOverview = text(root.path("overview").asText(""));
            String overview = rawOverview == null ? null : guardNumbers(rawOverview, allowedNumbers);
            if (rawOverview != null && overview == null) {
                droppedCount++;
            }
            List<String> findings = list(root.path("findings"), allowedNumbers, "findings");
            List<String> risks = list(root.path("risks"), allowedNumbers, "risks");
            List<String> notes = new ArrayList<>();
            int dropped = droppedCount;
            if (dropped > 0) {
                notes.add("AI 归纳中有 " + dropped + " 句包含无法核对的事实数字，已过滤");
            }
            if (overview == null && findings.isEmpty() && risks.isEmpty()) {
                return new Narrative(false, null, List.of(), List.of(),
                        List.of("AI 归纳内容为空或全部被过滤，报告仅保留事实部分"));
            }
            return new Narrative(true, overview, findings, risks, notes);
        } catch (Exception e) {
            log.warn("报告 AI 归纳不是合法 JSON：{}", e.getMessage());
            return new Narrative(false, null, List.of(), List.of(),
                    List.of("AI 归纳返回结构无法解析，报告仅保留事实部分"));
        }
    }

    private int droppedCount;

    private List<String> list(com.fasterxml.jackson.databind.JsonNode node, Set<String> allowed, String kind) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode item : node) {
            String value = text(item.asText(""));
            if (value == null) {
                continue;
            }
            String guarded = guardNumbers(value, allowed);
            if (guarded == null) {
                droppedCount++;
                continue;
            }
            result.add(guarded);
            if (result.size() >= 6) {
                break;
            }
        }
        return result;
    }

    /**
     * 过滤含未核对数字的句子。允许出现摘要里确实存在的数字（统计值、时间点）。
     * 含数字的句子整句丢弃，不做"改数字"这类会歪曲语义的处理。
     */
    String guardNumbers(String sentence, Set<String> allowedNumbers) {
        List<String> kept = new ArrayList<>();
        for (String part : sentence.split("(?<=[。；！？])")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            boolean hasUnknown = false;
            // 先去掉千分位，避免「5,000」被拆成 5 和 000 两个假数字
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+(?:\\.\\d+)?")
                    .matcher(trimmed.replaceAll("(?<=\\d),(?=\\d{3})", ""));
            while (matcher.find()) {
                if (!allowedNumbers.contains(normalizeNumber(matcher.group()))) {
                    hasUnknown = true;
                    break;
                }
            }
            if (!hasUnknown) {
                kept.add(trimmed);
            }
        }
        return kept.isEmpty() ? null : String.join("", kept);
    }

    private static String normalizeNumber(String raw) {
        if (raw.contains(".")) {
            String trimmed = raw.replaceAll("0+$", "");
            return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        }
        return raw;
    }

    private static Set<String> allowedNumbers(List<CaseEntity> cases, List<TodoEntity> todos,
                                              Map<String, Integer> stats) {
        Set<String> allowed = new LinkedHashSet<>();
        stats.values().forEach(value -> allowed.add(String.valueOf(value)));
        for (CaseEntity c : cases) {
            addNumber(allowed, c.getStartSec());
            addNumber(allowed, c.getEndSec());
            // 事项文本里本来就出现的数字（如「5,000 条」「¥3,999」）属于可核对事实，
            // 否则模型只要引用承诺里的原文数字就会被整句丢弃，报告反而失真。
            addTextNumbers(allowed, c.getQuestion());
            addTextNumbers(allowed, c.getClaim());
            addTextNumbers(allowed, c.getReasoning());
            addTextNumbers(allowed, c.getLimitations());
        }
        for (TodoEntity t : todos) {
            addTextNumbers(allowed, t.getTitle());
        }
        allowed.add("1");
        allowed.add("2");
        return allowed;
    }

    /** 把文本里出现的数字（去掉千分位）加入白名单。 */
    private static void addTextNumbers(Set<String> allowed, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+(?:\\.\\d+)?")
                .matcher(text.replaceAll("(?<=\\d),(?=\\d{3})", ""));
        while (matcher.find()) {
            allowed.add(normalizeNumber(matcher.group()));
        }
    }

    private static void addNumber(Set<String> allowed, BigDecimal value) {
        if (value != null) {
            allowed.add(normalizeNumber(value.stripTrailingZeros().toPlainString()));
        }
    }

    private static String extractJson(String content) {
        if (content == null) {
            throw new IllegalArgumentException("空内容");
        }
        String text = content.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("非 JSON 内容");
        }
        return text.substring(start, end + 1);
    }

    private static String text(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trim(trimmed, 600);
    }

    private static String trim(String value, int max) {
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "（无）" : value;
    }

    private static String labelOf(ReviewStatus status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case UNREVIEWED -> "尚未检查";
            case IN_REVIEW -> "核验中";
            case REVIEWED -> "已核验";
        };
    }

    private static String labelOf(AnswerStatus status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case ANSWERED -> "已回答";
            case PARTIALLY_ANSWERED -> "部分回答";
            case NOT_FOUND -> "未找到回答";
        };
    }

    private static String labelOf(EvidenceType type) {
        if (type == null) {
            return "未知";
        }
        return switch (type) {
            case VERBAL_CLAIM -> "口头承诺";
            case SPECIFICATION_DISPLAY -> "规格或界面展示";
            case OPERATIONAL_EVIDENCE -> "实际操作证据";
            case UNKNOWN -> "待确认";
        };
    }

    private static String labelOf(EvidenceSufficiency sufficiency) {
        if (sufficiency == null) {
            return "未知";
        }
        return switch (sufficiency) {
            case NOT_CHECKED -> "尚未评估";
            case INSUFFICIENT -> "证据不足";
            case SUFFICIENT -> "证据充分";
        };
    }

    private static String labelOf(TodoStatus status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case OPEN -> "待处理";
            case IN_PROGRESS -> "进行中";
            case DONE -> "已完成";
            case CANCELLED -> "已取消";
        };
    }

    static String join(List<String> values) {
        return values.stream().filter(v -> v != null && !v.isBlank()).collect(Collectors.joining("；"));
    }
}
