package com.liveproof.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.liveproof.ai.AiCandidateParser;
import com.liveproof.ai.AiClient;
import com.liveproof.ai.AiCompletion;
import com.liveproof.ai.AiPromptBuilder;
import com.liveproof.config.AiProperties;
import com.liveproof.dto.AiCandidateResponse;
import com.liveproof.dto.AiCaseCandidate;
import com.liveproof.dto.SmartSubtitleCuesDto;
import com.liveproof.dto.SubtitleCue;
import com.liveproof.dto.SubtitleResponse;
import com.liveproof.entity.CaseEntity;
import com.liveproof.entity.MaterialEntity;
import com.liveproof.exception.AiErrors;
import com.liveproof.exception.ApiException;
import com.liveproof.exception.BadRequestException;
import com.liveproof.mapper.CaseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 从字幕提取候选核验事项。
 *
 * <p>与 {@link AiReviewService} 的分工：那个是「对这一条事项找证据」，
 * 这个是「先从整段演示里找出有哪些事值得核验」。输出的是一张<b>待人工确认的选题清单</b>，
 * 不写库、不含任何核验结论，也不替用户勾选。
 *
 * <p>三个前置条件必须分开表达，不能混成一个「没有结果」：
 * <ol>
 *   <li>AI 未配置 → 503 {@code AI_NOT_CONFIGURED}</li>
 *   <li>素材没有可用字幕 → 409 {@code SUBTITLE_UNAVAILABLE}（或云端未就绪的 409）</li>
 *   <li>字幕有、模型也答了，但确实没有可核验的承诺 → 200 + 空数组</li>
 * </ol>
 */
@Service
public class AiCandidateService {

    private static final Logger log = LoggerFactory.getLogger(AiCandidateService.class);

    /** 字幕来源策略：默认先本地、再云端，两处都没有才报错。 */
    public enum SubtitleMode {
        AUTO, LOCAL, CLOUD;

        static SubtitleMode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return AUTO;
            }
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "auto" -> AUTO;
                case "local" -> LOCAL;
                case "cloud" -> CLOUD;
                default -> throw BadRequestException.invalidParameter(
                        "subtitleSource 只接受 auto / local / cloud，收到：" + raw);
            };
        }
    }

    /** 载入结果：实际用到的字幕及其出处。 */
    private record Bundle(String source, String video, String subtitleFile,
                          int totalCues, List<SubtitleCue> cues, boolean truncated) {
    }

    private final MaterialService materialService;
    private final SubtitleService subtitleService;
    private final SmartSubtitleService smartSubtitleService;
    private final AiClient aiClient;
    private final AiCandidateParser parser;
    private final AiProperties aiProperties;
    private final CaseMapper caseMapper;

    public AiCandidateService(MaterialService materialService,
                              SubtitleService subtitleService,
                              SmartSubtitleService smartSubtitleService,
                              AiClient aiClient,
                              AiCandidateParser parser,
                              AiProperties aiProperties,
                              CaseMapper caseMapper) {
        this.materialService = materialService;
        this.subtitleService = subtitleService;
        this.smartSubtitleService = smartSubtitleService;
        this.aiClient = aiClient;
        this.parser = parser;
        this.aiProperties = aiProperties;
        this.caseMapper = caseMapper;
    }

    public AiCandidateResponse generate(String materialId, String subtitleSource) {
        if (!aiClient.isConfigured()) {
            throw AiErrors.notConfigured();
        }
        SubtitleMode mode = SubtitleMode.parse(subtitleSource);
        MaterialEntity material = materialService.require(requireId(materialId));
        if (!material.isReady()) {
            throw new ApiException(HttpStatus.CONFLICT, "MATERIAL_NOT_READY",
                    "素材尚未就绪（当前状态：" + material.getStatus() + "），请等待转码完成后再生成候选事项");
        }

        long started = System.currentTimeMillis();
        Bundle bundle = load(material, mode);
        if (bundle.cues().isEmpty()) {
            throw AiErrors.subtitleUnavailable("字幕里没有任何可用的文本");
        }

        String sourceLabel = "cloud".equals(bundle.source()) ? "保利威智能字幕" : "本地 SRT 字幕";
        String user = AiPromptBuilder.candidateUserPrompt(
                material.getOriginalName() == null ? material.getFileName() : material.getOriginalName(),
                material.getId(), sourceLabel, durationOf(material), bundle.cues(), bundle.truncated());

        AiCompletion completion = aiClient.complete(AiPromptBuilder.CANDIDATE_SYSTEM, user, List.of(), true);
        AiCandidateParser.ParseResult parsed = parser.parse(
                completion.content(), bundle.cues(), durationOf(material), existingKeys(material.getId()));

        long elapsed = System.currentTimeMillis() - started;
        int duplicates = (int) parsed.candidates().stream().filter(AiCaseCandidate::duplicate).count();
        log.info("AI 候选事项生成完成 material={} source={} cues={} candidates={} dup={} elapsed={}ms",
                material.getId(), bundle.source(), bundle.cues().size(), parsed.candidates().size(),
                duplicates, elapsed);

        return new AiCandidateResponse(
                material.getId(),
                bundle.source(),
                bundle.subtitleFile(),
                bundle.totalCues(),
                bundle.cues().size(),
                bundle.truncated(),
                completion.model(),
                elapsed,
                parsed.candidates(),
                duplicates,
                parsed.notes());
    }

    // ------------------------------------------------------------------ 字幕

    /**
     * 按策略取字幕。
     *
     * <p>注意区分三种失败：<b>没有字幕</b>（404 视为「没有」，其余照抛）、
     * <b>字幕文件登记了却读不出来</b>（500，必须如实报错，不能当成没有字幕）,
     * 以及<b>云端任务没准备好</b>（409，保持原错误码让前端引导用户去创建任务）。
     */
    private Bundle load(MaterialEntity material, SubtitleMode mode) {
        // 与 CaseService.create 完全一致的视频引用口径：内置素材存文件名，云端素材存素材 id
        String video = material.getFileName() != null ? material.getFileName() : material.getId();
        List<String> localIssues = new ArrayList<>();
        List<String> cloudIssues = new ArrayList<>();

        if (mode != SubtitleMode.CLOUD) {
            try {
                SubtitleResponse response = subtitleService.forVideo(video);
                if (!response.cues().isEmpty()) {
                    Sampling sampling = sample(response.cues());
                    return new Bundle("local", video, response.subtitleFile(),
                            response.cues().size(), sampling.cues(), sampling.truncated());
                }
                localIssues.add("本地字幕解析出 0 条文本");
            } catch (ApiException e) {
                if (!"SUBTITLE_NOT_FOUND".equals(e.getCode())) {
                    throw e;
                }
                if (mode == SubtitleMode.LOCAL) {
                    throw AiErrors.subtitleUnavailable("该素材还没有本地 SRT 字幕，请先在素材与字幕页上传");
                }
                localIssues.add("没有本地 SRT 字幕");
            }
        }

        if (mode != SubtitleMode.LOCAL) {
            try {
                SmartSubtitleCuesDto cues = smartSubtitleService.readyCuesFor(video, null, null);
                if (!cues.cues().isEmpty()) {
                    Sampling sampling = sample(cues.cues());
                    return new Bundle("cloud", video, null, cues.cues().size(),
                            sampling.cues(), sampling.truncated());
                }
                cloudIssues.add("云端字幕解析出 0 条文本");
            } catch (ApiException e) {
                if (mode == SubtitleMode.CLOUD) {
                    // 云端是用户明确指定的来源，原样抛出（前端据此提示「去创建智能字幕任务」）
                    throw e;
                }
                cloudIssues.add("云端没有就绪的智能字幕任务");
            }
        }

        List<String> issues = new ArrayList<>(localIssues);
        issues.addAll(cloudIssues);
        throw AiErrors.subtitleUnavailable(String.join("；", issues)
                + "。可上传 SRT 或创建保利威智能字幕任务后重试");
    }

    // ------------------------------------------------------------------ 抽样与去重

    /** 均匀抽样结果。 */
    private record Sampling(List<SubtitleCue> cues, boolean truncated) {
    }

    /**
     * 超过字符预算时<b>均匀抽样</b>（隔一条取一条），而不是从尾部截断。
     *
     * <p>截断会让模型只看到前半段，后半段的承诺全部漏掉，而它并不知道自己漏了；
     * 均匀抽样至少覆盖全片，且提示词里会明确告知「这是抽样结果」。
     */
    private Sampling sample(List<SubtitleCue> cues) {
        int budget = Math.max(500, aiProperties.getMaxSubtitleChars());
        if (chars(cues) <= budget) {
            return new Sampling(cues, false);
        }
        List<SubtitleCue> current = cues;
        while (chars(current) > budget && current.size() > 1) {
            List<SubtitleCue> next = new ArrayList<>();
            for (int i = 0; i < current.size(); i += 2) {
                next.add(current.get(i));
            }
            if (next.size() == current.size()) {
                break;
            }
            current = next;
        }
        return new Sampling(current, true);
    }

    private static int chars(List<SubtitleCue> cues) {
        return cues.stream().mapToInt(cue -> cue.text() == null ? 0 : cue.text().length()).sum();
    }

    private Double durationOf(MaterialEntity material) {
        return material.getDurationSec() == null ? null : material.getDurationSec().doubleValue();
    }

    /** 库内该素材已有事项：归一问题 → case id。同一句话不允许在列表里出现两份。 */
    private Map<String, String> existingKeys(String materialId) {
        List<CaseEntity> list = caseMapper.selectList(Wrappers.lambdaQuery(CaseEntity.class)
                .eq(CaseEntity::getMaterialId, materialId));
        Map<String, String> keys = new LinkedHashMap<>();
        for (CaseEntity entity : list) {
            if (entity.getQuestion() != null && !entity.getQuestion().isBlank()) {
                keys.putIfAbsent(CaseImportService.key(entity.getQuestion()), entity.getId());
            }
        }
        return keys;
    }

    private static String requireId(String materialId) {
        String id = materialId == null ? "" : materialId.trim();
        if (id.isEmpty()) {
            throw BadRequestException.invalidParameter(
                    "materialId 不能为空：候选事项必须归属于某个素材");
        }
        return id;
    }
}
