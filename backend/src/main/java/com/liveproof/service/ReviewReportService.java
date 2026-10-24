package com.liveproof.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liveproof.dto.ReportStats;
import com.liveproof.dto.ReviewReportResponse;
import com.liveproof.dto.SubtitleCue;
import com.liveproof.entity.CaseEntity;
import com.liveproof.entity.TodoEntity;
import com.liveproof.enums.AnswerStatus;
import com.liveproof.enums.EvidenceSufficiency;
import com.liveproof.enums.EvidenceType;
import com.liveproof.enums.ReviewStatus;
import com.liveproof.enums.TodoStatus;
import com.liveproof.exception.BadRequestException;
import com.liveproof.exception.NotFoundException;
import com.liveproof.mapper.CaseMapper;
import com.liveproof.mapper.TodoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 复盘报告生成。
 *
 * <p>数据来源严格限定为数据库里<b>已保存</b>的用例四字段、核验依据/局限、关联时间点与待办；
 * 不读取前端编辑态，也不使用未采纳的 AI 建议。
 *
 * <p>统计全部在服务端计算；AI 只负责归纳（见 {@link ReportNarrativeService}），
 * 且其输出不能改变任何已保存状态。
 */
@Service
public class ReviewReportService {

    private static final Logger log = LoggerFactory.getLogger(ReviewReportService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CaseMapper caseMapper;
    private final TodoMapper todoMapper;
    private final SubtitleService subtitleService;
    private final ReportNarrativeService narrativeService;
    private final MaterialService materialService;
    private final ObjectMapper objectMapper;

    public ReviewReportService(CaseMapper caseMapper, TodoMapper todoMapper,
                               SubtitleService subtitleService,
                               ReportNarrativeService narrativeService,
                               MaterialService materialService,
                               ObjectMapper objectMapper) {
        this.caseMapper = caseMapper;
        this.todoMapper = todoMapper;
        this.subtitleService = subtitleService;
        this.narrativeService = narrativeService;
        this.materialService = materialService;
        this.objectMapper = objectMapper;
    }

    /** 报告文件名（下载用）。 */
    public String fileName(String ds) {
        return fileName(ds, null);
    }

    /**
     * 报告文件名（下载用）。
     *
     * @param materialId 非空时按素材维度命名，避免同一数据集下多份报告同名覆盖
     */
    public String fileName(String ds, String materialId) {
        String scope = ds == null || ds.isBlank() ? "all" : ds;
        if (materialId != null && !materialId.isBlank()) {
            scope = scope + "-" + materialId.trim();
        }
        return "liveproof-report-" + scope + "-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".md";
    }

    public ReviewReportResponse generate(String ds) {
        return generate(ds, null);
    }

    /**
     * 生成复盘报告。
     *
     * <p>{@code materialId} 非空时把范围收窄到「该素材下的核验事项」，用于一场演示对应一个素材的复盘场景。
     * 待办仍按<b>这些事项</b>过滤后统计——否则会出现「0 条事项却统计出 5 条待办」的矛盾。
     *
     * @param ds         数据集过滤，可为空
     * @param materialId 素材过滤，可为空
     */
    public ReviewReportResponse generate(String ds, String materialId) {
        String dataset = ds == null || ds.isBlank() ? null : ds.trim();
        String material = materialId == null || materialId.isBlank() ? null : materialId.trim();
        LambdaQueryWrapper<CaseEntity> wrapper = Wrappers.lambdaQuery(CaseEntity.class);
        if (dataset != null) {
            wrapper.eq(CaseEntity::getDs, dataset);
        }
        if (material != null) {
            // 先确认素材存在，避免把「素材 id 打错」说成「没有可复盘的用例」
            materialService.require(material);
            wrapper.eq(CaseEntity::getMaterialId, material);
        }
        wrapper.orderByAsc(CaseEntity::getStartSec).orderByAsc(CaseEntity::getId);
        List<CaseEntity> cases = caseMapper.selectList(wrapper);
        if (cases.isEmpty()) {
            throw BadRequestException.invalidParameter(
                    "数据集 " + (dataset == null ? "(全部)" : dataset)
                            + (material == null ? "" : " 素材 " + material) + " 没有可复盘的用例");
        }

        QueryWrapper<TodoEntity> todoWrapper = new QueryWrapper<>();
        todoWrapper.orderByDesc("created_at").orderByDesc("id");
        List<TodoEntity> todos = todoMapper.selectList(todoWrapper);
        if (material != null) {
            Set<String> caseIds = cases.stream().map(CaseEntity::getId).collect(Collectors.toSet());
            todos = todos.stream().filter(todo -> caseIds.contains(todo.getCaseId())).toList();
        }

        String label = datasetLabel(dataset, cases, material);
        ReportStats stats = stats(cases, todos);
        Map<String, List<SubtitleCue>> cueCache = new LinkedHashMap<>();

        ReportNarrativeService.Narrative narrative =
                narrativeService.build(label, cases, todos, statsMap(stats));

        List<String> notes = new ArrayList<>(narrative.notes());
        if (!narrative.used() && narrative.notes().isEmpty()) {
            notes.add("本次报告未包含 AI 归纳");
        }

        String markdown = render(dataset, material, label, cases, todos, stats, narrative, cueCache);
        log.info("生成复盘报告 ds={} material={} cases={} todos={} aiUsed={} chars={}",
                dataset == null ? "all" : dataset, material == null ? "all" : material,
                cases.size(), todos.size(), narrative.used(), markdown.length());

        String responseScope = material == null ? (dataset == null ? "all" : dataset) : material;
        return new ReviewReportResponse(responseScope, label,
                LocalDateTime.now().withNano(0).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                stats, narrative.used(), narrative.used() ? "DeepSeek" : null, notes, markdown);
    }

    // ---------- 统计 ----------

    ReportStats stats(List<CaseEntity> cases, List<TodoEntity> todos) {
        int reviewed = count(cases, c -> c.getReviewStatus() == ReviewStatus.REVIEWED);
        int inReview = count(cases, c -> c.getReviewStatus() == ReviewStatus.IN_REVIEW);
        int unreviewed = count(cases, c -> c.getReviewStatus() == null
                || c.getReviewStatus() == ReviewStatus.UNREVIEWED);
        int answered = count(cases, c -> c.getAnswerStatus() == AnswerStatus.ANSWERED);
        int partial = count(cases, c -> c.getAnswerStatus() == AnswerStatus.PARTIALLY_ANSWERED);
        int notFound = count(cases, c -> c.getAnswerStatus() == AnswerStatus.NOT_FOUND);
        int operational = count(cases, c -> c.getEvidenceType() == EvidenceType.OPERATIONAL_EVIDENCE);
        int spec = count(cases, c -> c.getEvidenceType() == EvidenceType.SPECIFICATION_DISPLAY);
        int verbal = count(cases, c -> c.getEvidenceType() == EvidenceType.VERBAL_CLAIM);
        int unknown = count(cases, c -> c.getEvidenceType() == null
                || c.getEvidenceType() == EvidenceType.UNKNOWN);
        int sufficient = count(cases, c -> c.getEvidenceSufficiency() == EvidenceSufficiency.SUFFICIENT);
        int insufficient = count(cases, c -> c.getEvidenceSufficiency() == EvidenceSufficiency.INSUFFICIENT);
        int notChecked = count(cases, c -> c.getEvidenceSufficiency() == null
                || c.getEvidenceSufficiency() == EvidenceSufficiency.NOT_CHECKED);

        return new ReportStats(cases.size(), reviewed, inReview, unreviewed,
                answered, partial, notFound,
                operational, spec, verbal, unknown,
                sufficient, insufficient, notChecked,
                todos.size(),
                todoCount(todos, TodoStatus.OPEN),
                todoCount(todos, TodoStatus.IN_PROGRESS),
                todoCount(todos, TodoStatus.DONE),
                todoCount(todos, TodoStatus.CANCELLED));
    }

    private static Map<String, Integer> statsMap(ReportStats stats) {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("复盘事项总数", stats.total());
        map.put("已核验（人工标记为已核验）", stats.reviewed());
        map.put("核验中", stats.inReview());
        map.put("尚未检查", stats.unreviewed());
        map.put("回答状态-已回答", stats.answered());
        map.put("回答状态-部分回答", stats.partial());
        map.put("回答状态-未找到回答", stats.notFound());
        map.put("证据形态-实际操作证据", stats.operational());
        map.put("证据形态-规格或界面展示", stats.specificationDisplay());
        map.put("证据形态-口头承诺", stats.verbalClaim());
        map.put("证据形态-待确认", stats.evidenceUnknown());
        map.put("证据充分性-充分", stats.sufficient());
        map.put("证据充分性-不足", stats.insufficient());
        map.put("证据充分性-尚未评估", stats.notChecked());
        map.put("待办总数", stats.todoTotal());
        map.put("待办-待处理", stats.todoOpen());
        map.put("待办-进行中", stats.todoInProgress());
        map.put("待办-已完成", stats.todoDone());
        map.put("待办-已取消", stats.todoCancelled());
        return map;
    }

    private static int count(List<CaseEntity> cases, java.util.function.Predicate<CaseEntity> predicate) {
        return (int) cases.stream().filter(predicate).count();
    }

    private static int todoCount(List<TodoEntity> todos, TodoStatus status) {
        return (int) todos.stream().filter(todo -> todo.getStatus() == status).count();
    }

    // ---------- Markdown ----------

    String render(String dataset, String materialId, String label, List<CaseEntity> cases, List<TodoEntity> todos,
                  ReportStats stats, ReportNarrativeService.Narrative narrative,
                  Map<String, List<SubtitleCue>> cueCache) {
        StringBuilder md = new StringBuilder();
        md.append("# LiveProof 复盘报告 · ").append(label).append("\n\n");
        md.append("> - 数据集：`").append(dataset == null ? "all" : dataset).append("` · ").append(label)
                .append("（").append(stats.total()).append(" 条复盘事项）\n");
        if (materialId != null) {
            md.append("> - 范围：仅统计素材 `").append(materialId).append("` 下的核验事项与待办\n");
        }
        md.append("> - 生成时间：").append(LocalDateTime.now().format(STAMP)).append("\n");
        md.append("> - 数据来源：数据库 `cases` / `todos` 中**已保存**的核验结果与待办；")
                .append("未保存的编辑状态与未采纳的 AI 建议不计入\n");
        md.append("> - 生成方式：统计与逐项事实由服务端计算；");
        if (narrative.used()) {
            md.append("第 2 节「AI 归纳」由模型生成并单独标注，未改动任何已保存状态\n\n");
        } else {
            md.append("本次未启用模型归纳，报告仅包含可追溯事实\n\n");
        }

        // 1. 概览
        md.append("## 1. 复盘概览\n\n");
        md.append("| 指标 | 数量 | 说明 |\n|---|---:|---|\n");
        row(md, "复盘事项总数", stats.total(), "数据集内全部事项");
        row(md, "已核验", stats.reviewed(), "人工标记为「已核验」");
        row(md, "核验中", stats.inReview(), "人工标记为「核验中」");
        row(md, "尚未检查", stats.unreviewed(), "仍为「尚未检查」，报告中保持原状");
        row(md, "证据充分", stats.sufficient(), "人工判定证据足以支撑结论");
        row(md, "证据不足", stats.insufficient(), "人工判定证据不足");
        row(md, "尚未评估", stats.notChecked(), "充分性仍为「尚未评估」");
        md.append("\n");

        // 2. 关键发现
        md.append("## 2. 关键发现\n\n");
        if (narrative.used()) {
            md.append("> AI 归纳（模型生成，供参考）：以下内容不得替代人工判断，")
                    .append("数字以第 1 节服务端统计为准。\n\n");
            if (narrative.overview() != null) {
                md.append(narrative.overview()).append("\n\n");
            }
            if (!narrative.findings().isEmpty()) {
                narrative.findings().forEach(item -> md.append("- ").append(item).append('\n'));
                md.append('\n');
            }
            if (!narrative.risks().isEmpty()) {
                md.append("**待确认的风险**\n\n");
                narrative.risks().forEach(item -> md.append("- ").append(item).append('\n'));
                md.append('\n');
            }
        }
        md.append("### 2.1 按证据形态归类（事实，来自已保存字段）\n\n");
        groupByEvidence(md, cases, EvidenceType.OPERATIONAL_EVIDENCE, "实际操作证据");
        groupByEvidence(md, cases, EvidenceType.SPECIFICATION_DISPLAY, "规格或界面展示");
        groupByEvidence(md, cases, EvidenceType.VERBAL_CLAIM, "口头承诺");
        groupByEvidence(md, cases, EvidenceType.UNKNOWN, "待确认（未归类）");
        md.append("\n### 2.2 按回答状态归类\n\n");
        groupByAnswer(md, cases, AnswerStatus.ANSWERED, "已回答");
        groupByAnswer(md, cases, AnswerStatus.PARTIALLY_ANSWERED, "部分回答");
        groupByAnswer(md, cases, AnswerStatus.NOT_FOUND, "未找到回答");
        groupByUncheckedAnswer(md, cases);
        md.append("\n> 说明：以上归类只描述「视频里怎么说的、证据长什么样」，")
                .append("不代表内容为真，也不代表功能已实现。\n\n");

        // 3. 逐项复盘
        md.append("## 3. 逐项复盘\n\n");
        int index = 1;
        for (CaseEntity c : cases) {
            md.append("### 3.").append(index++).append(" `").append(c.getId()).append("` ")
                    .append(nullToDash(c.getQuestion())).append("\n\n");
            md.append("- 功能承诺：").append(nullToDash(c.getClaim())).append('\n');
            md.append("- 已保存状态：核验进度 **").append(label(c.getReviewStatus()))
                    .append("**；回答状态 **").append(label(c.getAnswerStatus()))
                    .append("**；证据形态 **").append(label(c.getEvidenceType()))
                    .append("**；证据充分性 **").append(label(c.getEvidenceSufficiency())).append("**\n");
            md.append("- 核验依据：").append(nullToDash(c.getReasoning())).append('\n');
            md.append("- 局限与风险：").append(nullToDash(c.getLimitations())).append('\n');
            md.append("- 数据源标注演示区间：").append(range(c.getStartSec(), c.getEndSec())).append('\n');
            List<BigDecimal> points = timePoints(c);
            if (points.isEmpty()) {
                md.append("- 可追溯时间点：（未标记人工时间点）\n");
            } else {
                md.append("- 可追溯时间点：").append(points.stream()
                        .map(p -> "`" + fmt(p.doubleValue()) + "`")
                        .collect(Collectors.joining("、"))).append('\n');
            }
            List<TodoEntity> related = todos.stream()
                    .filter(todo -> c.getId().equals(todo.getCaseId())).toList();
            if (!related.isEmpty()) {
                md.append("- 关联待办：").append(related.stream()
                        .map(todo -> todo.getTitle() + "（" + label(todo.getStatus()) + "）")
                        .collect(Collectors.joining("；"))).append('\n');
            }
            md.append('\n');
        }

        // 4. 证据索引
        md.append("## 4. 证据索引\n\n");
        md.append("| # | 时间点 | 对应事项 | 依据来源 |\n|---:|---|---|---|\n");
        int rowIndex = 1;
        for (CaseEntity c : cases) {
            List<BigDecimal> points = timePoints(c);
            if (points.isEmpty()) {
                continue;
            }
            List<SubtitleCue> cues = cuesOf(c.getVideo(), cueCache);
            for (BigDecimal point : points) {
                String subtitle = cues.stream()
                        .filter(cue -> point.doubleValue() >= cue.startSec() - 0.5
                                && point.doubleValue() <= cue.endSec() + 0.5)
                        .map(SubtitleCue::text)
                        .map(text -> text.replace("\n", " "))
                        .findFirst()
                        .orElse("（该时刻无字幕，需人工回看画面）");
                md.append("| ").append(rowIndex++).append(" | `").append(fmt(point.doubleValue()))
                        .append("` | `").append(c.getId()).append("` | ")
                        .append(escapeCell(subtitle)).append(" |\n");
            }
        }
        if (rowIndex == 1) {
            md.append("| - | - | - | 全部事项均未标记人工时间点 |\n");
        }
        md.append('\n');

        // 5. 后续行动
        md.append("## 5. 后续行动\n\n");
        if (todos.isEmpty()) {
            md.append("当前没有待办记录。\n\n");
        } else {
            md.append("| 待办 | 关联事项 | 状态 | 更新时间 |\n|---|---|---|---|\n");
            for (TodoEntity todo : todos) {
                md.append("| ").append(escapeCell(todo.getTitle()))
                        .append(" | `").append(todo.getCaseId()).append("` | ")
                        .append(label(todo.getStatus()))
                        .append(" | ").append(todo.getUpdatedAt() == null ? "—"
                                : todo.getUpdatedAt().format(STAMP))
                        .append(" |\n");
            }
            md.append('\n');
            md.append("待办统计：待处理 ").append(stats.todoOpen())
                    .append(" · 进行中 ").append(stats.todoInProgress())
                    .append(" · 已完成 ").append(stats.todoDone())
                    .append(" · 已取消 ").append(stats.todoCancelled()).append("\n\n");
        }

        // 6. 未确认事项
        md.append("## 6. 未确认事项\n\n");
        List<CaseEntity> unchecked = cases.stream()
                .filter(c -> c.getReviewStatus() == null || c.getReviewStatus() == ReviewStatus.UNREVIEWED)
                .toList();
        md.append("### 6.1 尚未人工检查（").append(unchecked.size()).append(" 项）\n\n");
        if (unchecked.isEmpty()) {
            md.append("无。\n\n");
        } else {
            unchecked.forEach(c -> md.append("- `").append(c.getId()).append("` ")
                    .append(nullToDash(c.getQuestion())).append('\n'));
            md.append('\n');
        }

        List<CaseEntity> weakEvidence = cases.stream()
                .filter(c -> c.getAnswerStatus() == AnswerStatus.NOT_FOUND
                        || c.getAnswerStatus() == AnswerStatus.PARTIALLY_ANSWERED
                        || c.getEvidenceSufficiency() == EvidenceSufficiency.INSUFFICIENT
                        || c.getEvidenceSufficiency() == EvidenceSufficiency.NOT_CHECKED)
                .toList();
        md.append("### 6.2 回答不完整或证据不足（").append(weakEvidence.size()).append(" 项）\n\n");
        if (weakEvidence.isEmpty()) {
            md.append("无。\n\n");
        } else {
            weakEvidence.forEach(c -> md.append("- `").append(c.getId()).append("` ")
                    .append(nullToDash(c.getQuestion()))
                    .append("｜回答状态：").append(label(c.getAnswerStatus()))
                    .append("；充分性：").append(label(c.getEvidenceSufficiency()))
                    .append('\n'));
            md.append('\n');
        }
        md.append("> 口径提醒：本次在当前视频片段中未找到证据，只说明**本段演示没有证明**，")
                .append("不等于该功能不存在；需另行补录演示后再判定。\n\n");

        // 附录
        md.append("## 附录 · 生成说明\n\n");
        if (!narrative.notes().isEmpty()) {
            narrative.notes().forEach(note -> md.append("- ").append(note).append('\n'));
        }
        md.append("- 四个核验字段相互独立，任一取值组合都不等于「内容为真」。\n");
        md.append("- 统计数字由服务端按数据库真实记录计算；AI 归纳不参与统计，")
                .append("其输出中无法核对的数字表述会被过滤。\n");
        md.append("- 本报告为即时生成，未持久化为新表；每次生成反映生成时刻的数据库状态。\n");
        return md.toString();
    }

    private void groupByEvidence(StringBuilder md, List<CaseEntity> cases, EvidenceType type, String title) {
        List<CaseEntity> group = cases.stream().filter(c -> typeOf(c) == type).toList();
        md.append("**").append(title).append("（").append(group.size()).append("）**\n\n");
        if (group.isEmpty()) {
            md.append("- 无\n\n");
            return;
        }
        group.forEach(c -> md.append("- `").append(c.getId()).append("` ")
                .append(nullToDash(c.getQuestion()))
                .append("｜充分性：").append(label(c.getEvidenceSufficiency()))
                .append('\n'));
        md.append('\n');
    }

    private void groupByAnswer(StringBuilder md, List<CaseEntity> cases, AnswerStatus status, String title) {
        // 按真实取值比较：answerStatus 为 NULL 的行不能落进 not_found 组，
        // 那会把「还没核验」统计成「已经判定为没找到回答」（V8 起 NULL = 未核验）
        List<CaseEntity> group = cases.stream().filter(c -> c.getAnswerStatus() == status).toList();
        md.append("**").append(title).append("（").append(group.size()).append("）**\n\n");
        if (group.isEmpty()) {
            md.append("- 无\n\n");
            return;
        }
        group.forEach(c -> md.append("- `").append(c.getId()).append("` ")
                .append(nullToDash(c.getQuestion())).append('\n'));
        md.append('\n');
    }

    /**
     * 「尚未核验」单独成组：answerStatus 为 NULL 的行还没被判定过，
     * 既不能算 not_found，也不该从归类里消失。
     */
    private void groupByUncheckedAnswer(StringBuilder md, List<CaseEntity> cases) {
        List<CaseEntity> group = cases.stream().filter(c -> c.getAnswerStatus() == null).toList();
        md.append("**尚未核验（").append(group.size()).append("）**\n\n");
        if (group.isEmpty()) {
            md.append("- 无\n\n");
            return;
        }
        group.forEach(c -> md.append("- `").append(c.getId()).append("` ")
                .append(nullToDash(c.getQuestion())).append('\n'));
        md.append('\n');
    }

    private static EvidenceType typeOf(CaseEntity c) {
        return c.getEvidenceType() == null ? EvidenceType.UNKNOWN : c.getEvidenceType();
    }

    private List<SubtitleCue> cuesOf(String video, Map<String, List<SubtitleCue>> cache) {
        if (video == null || video.isBlank()) {
            return List.of();
        }
        return cache.computeIfAbsent(video, key -> {
            try {
                return subtitleService.forVideo(key).cues();
            } catch (Exception e) {
                log.info("报告取字幕失败 video={}：{}", key, e.getMessage());
                return List.of();
            }
        });
    }

    private List<BigDecimal> timePoints(CaseEntity c) {
        if (c.getTimePoints() == null || c.getTimePoints().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(c.getTimePoints(), new TypeReference<List<BigDecimal>>() {
            });
        } catch (Exception e) {
            log.warn("用例 {} 的 time_points 解析失败，报告中按无时间点处理", c.getId());
            return List.of();
        }
    }

    private String datasetLabel(String dataset, List<CaseEntity> cases, String materialId) {
        if (materialId != null) {
            var material = materialService.findByRef(materialId);
            String name = material == null ? materialId : material.getOriginalName();
            return name + "（单素材复盘）";
        }
        if ("cf".equals(dataset)) {
            return "CandidateFlow 招聘管理系统（自制）";
        }
        if ("lc".equals(dataset)) {
            return "外部示例视频（素材不可公开演示）";
        }
        return Optional.ofNullable(cases.get(0).getDs()).orElse("全部数据集");
    }

    private static void row(StringBuilder md, String name, int value, String note) {
        md.append("| ").append(name).append(" | ").append(value).append(" | ").append(note).append(" |\n");
    }

    private static String escapeCell(String value) {
        return value == null ? "—" : value.replace("|", "\\|").replace("\n", " ");
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "（未填写）" : value;
    }

    private static String range(BigDecimal start, BigDecimal end) {
        if (start == null && end == null) {
            return "未标注";
        }
        return fmt(start == null ? 0 : start.doubleValue()) + " - "
                + (end == null ? "视频末尾" : fmt(end.doubleValue()));
    }

    static String fmt(double sec) {
        int total = (int) Math.round(Math.max(0, sec));
        return String.format("%02d:%02d", total / 60, total % 60);
    }

    private static String label(ReviewStatus status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case UNREVIEWED -> "尚未检查";
            case IN_REVIEW -> "核验中";
            case REVIEWED -> "已核验";
        };
    }

    private static String label(AnswerStatus status) {
        if (status == null) {
            // NULL 不是「未知」也不是「未找到回答」：它只表示这条还没有被判定过（V8 起）
            return "尚未核验";
        }
        return switch (status) {
            case ANSWERED -> "已回答";
            case PARTIALLY_ANSWERED -> "部分回答";
            case NOT_FOUND -> "未找到回答";
        };
    }

    private static String label(EvidenceType type) {
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

    private static String label(EvidenceSufficiency sufficiency) {
        if (sufficiency == null) {
            return "未知";
        }
        return switch (sufficiency) {
            case NOT_CHECKED -> "尚未评估";
            case INSUFFICIENT -> "证据不足";
            case SUFFICIENT -> "证据充分";
        };
    }

    private static String label(TodoStatus status) {
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
}
