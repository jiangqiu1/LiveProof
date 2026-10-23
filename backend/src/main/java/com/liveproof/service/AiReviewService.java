package com.liveproof.service;

import com.liveproof.ai.AiClient;
import com.liveproof.ai.AiCompletion;
import com.liveproof.ai.AiPromptBuilder;
import com.liveproof.ai.AiSuggestionParser;
import com.liveproof.ai.FrameImage;
import com.liveproof.config.AiProperties;
import com.liveproof.config.PolyvProperties;
import com.liveproof.dto.AiContextInfo;
import com.liveproof.dto.AiCueRef;
import com.liveproof.dto.AiFrameRef;
import com.liveproof.dto.AiReviewResponse;
import com.liveproof.dto.AiStatusDto;
import com.liveproof.dto.SubtitleCue;
import com.liveproof.entity.CaseEntity;
import com.liveproof.enums.SubtitleSource;
import com.liveproof.exception.AiErrors;
import com.liveproof.exception.NotFoundException;
import com.liveproof.mapper.CaseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 辅助核验。
 *
 * <p>链路：用例 → 字幕定位 → 少量关键帧 → 模型 → 结构化校验 → 建议。
 *
 * <p><b>只读</b>：本类不调用任何写库操作，也不返回 reviewStatus。
 * AI 建议必须由人工在前端逐项选择后，走原来的 PATCH 接口保存。
 */
@Service
public class AiReviewService {

    private static final Logger log = LoggerFactory.getLogger(AiReviewService.class);

    private final CaseMapper caseMapper;
    private final EvidenceContextService contextService;
    /** 本地 FFmpeg 取帧，只用于探测本机能力（云端素材走 materialFrameService）。 */
    private final FrameExtractionService frameService;
    /** 按素材存储类型分派取帧：本地 FFmpeg / 保利威截图。 */
    private final MaterialFrameService materialFrameService;
    private final AiClient aiClient;
    private final AiSuggestionParser parser;
    private final AiProperties aiProperties;
    private final PolyvProperties polyvProperties;

    public AiReviewService(CaseMapper caseMapper,
                           EvidenceContextService contextService,
                           FrameExtractionService frameService,
                           MaterialFrameService materialFrameService,
                           AiClient aiClient,
                           AiSuggestionParser parser,
                           AiProperties aiProperties,
                           PolyvProperties polyvProperties) {
        this.caseMapper = caseMapper;
        this.contextService = contextService;
        this.frameService = frameService;
        this.materialFrameService = materialFrameService;
        this.aiClient = aiClient;
        this.parser = parser;
        this.aiProperties = aiProperties;
        this.polyvProperties = polyvProperties;
    }

    public AiStatusDto status() {
        boolean ffmpeg = frameService.isFfmpegAvailable();
        boolean upload = polyvProperties.uploadConfigured();
        boolean snapshot = polyvProperties.vodConfigured();
        String note;
        if (!aiClient.isConfigured()) {
            note = "未配置 DEEPSEEK_API_KEY，AI 辅助核验不可用";
        } else if (!ffmpeg && !snapshot) {
            note = "AI 已配置，但本机 FFmpeg 与保利威截图均不可用，本次只能基于字幕判断";
        } else if (!ffmpeg) {
            note = "AI 已配置，本机未找到 FFmpeg；内置示例素材只能基于字幕判断，云端素材可用保利威整秒截图";
        } else {
            note = "AI 已配置，可结合字幕与关键帧分析";
        }
        return new AiStatusDto(aiClient.isConfigured(), aiClient.model(), aiClient.endpointHost(),
                aiProperties.isVisionEnabled(), ffmpeg, upload, snapshot,
                aiProperties.getMaxFrames(), note);
    }

    /** 默认按本地 SRT 字幕定位（既有行为不变）。 */
    public AiReviewResponse review(String caseId) {
        return review(caseId, SubtitleSource.LOCAL);
    }

    /**
     * 对单条用例发起一次 AI 分析。
     *
     * @param source 用哪份字幕做证据定位；cloud 时若没有就绪的智能字幕任务会抛 409，
     *               不会退化成「无字幕分析」
     */
    public AiReviewResponse review(String caseId, SubtitleSource source) {
        SubtitleSource effective = source == null ? SubtitleSource.LOCAL : source;
        if (!aiClient.isConfigured()) {
            throw AiErrors.notConfigured();
        }
        CaseEntity entity = caseMapper.selectById(caseId);
        if (entity == null) {
            throw NotFoundException.caseNotFound(caseId);
        }
        String video = entity.getVideo();
        if (video == null || video.isBlank()) {
            throw AiErrors.invalidResponse("该用例没有关联视频，无法进行证据分析");
        }

        long started = System.currentTimeMillis();
        EvidenceContextService.Prepared prepared = contextService.build(
                video, entity.getStartSec(), entity.getEndSec(), entity.getQuestion(), entity.getClaim(), effective);

        List<FrameExtractionService.Frame> extracted = List.of();
        String frameNote = null;
        if (!aiProperties.isVisionEnabled()) {
            frameNote = "配置关闭了画面核验（liveproof.ai.vision-enabled=false），本次仅基于字幕判断";
        } else {
            FrameExtractionService.Extraction extraction =
                    materialFrameService.extract(video, prepared.frameTimes());
            extracted = extraction.frames();
            frameNote = extraction.note();
        }

        List<FrameImage> images = new ArrayList<>();
        int bytes = 0;
        for (FrameExtractionService.Frame frame : extracted) {
            if (bytes + frame.jpeg().length > aiProperties.getRequestMaxImageBytes()) {
                frameNote = appendNote(frameNote, "关键帧总体积超过单次请求上限，后续帧未提交");
                break;
            }
            bytes += frame.jpeg().length;
            images.add(new FrameImage(frame.sec(), "image/jpeg", frame.jpeg()));
        }
        boolean visionUsed = !images.isEmpty();

        String system = AiPromptBuilder.REVIEW_SYSTEM;
        String user = AiPromptBuilder.reviewUserPrompt(entity, prepared, images, visionUsed, frameNote);

        AiCompletion completion = aiClient.complete(system, user, images, true);
        AiSuggestionParser.ParseResult parsed = parser.parse(
                completion.content(), prepared, entity.getStartSec(), entity.getEndSec());
        if (!visionUsed) {
            List<String> notes = new ArrayList<>(parsed.notes());
            notes.add("本次未进行画面核验，模型判断仅基于字幕：" + (frameNote == null ? "未提取到关键帧" : frameNote));
            parsed = new AiSuggestionParser.ParseResult(parsed.suggestion(), notes);
        }

        AiContextInfo context = new AiContextInfo(
                video,
                effective,
                prepared.durationSec(),
                prepared.subtitleFile(),
                prepared.totalCues(),
                toCueRefs(prepared),
                toFrameRefs(extracted),
                visionUsed,
                frameNote,
                !prepared.outside().isEmpty(),
                completion.model(),
                user.length(),
                completion.promptTokens(),
                completion.completionTokens(),
                System.currentTimeMillis() - started);

        log.info("AI 核验完成 case={} source={} cues={} frames={} vision={} elapsed={}ms",
                caseId, effective.getValue(), prepared.cues().size(), images.size(), visionUsed,
                System.currentTimeMillis() - started);

        return new AiReviewResponse(caseId, parsed.suggestion(), context, parsed.notes(),
                LocalDateTime.now().withNano(0).toString());
    }

    private static String appendNote(String existing, String extra) {
        if (existing == null || existing.isBlank()) {
            return extra;
        }
        return existing + "；" + extra;
    }

    private static List<AiCueRef> toCueRefs(EvidenceContextService.Prepared prepared) {
        List<AiCueRef> refs = new ArrayList<>();
        for (SubtitleCue cue : prepared.cues()) {
            refs.add(new AiCueRef(cue.index(), dec(cue.startSec()), dec(cue.endSec()),
                    cue.text().replace("\n", " "), prepared.outside().contains(cue.index())));
        }
        return refs;
    }

    private static List<AiFrameRef> toFrameRefs(List<FrameExtractionService.Frame> frames) {
        List<AiFrameRef> refs = new ArrayList<>();
        for (FrameExtractionService.Frame frame : frames) {
            String dataUrl = "data:image/jpeg;base64,"
                    + java.util.Base64.getEncoder().encodeToString(frame.jpeg());
            refs.add(new AiFrameRef(dec(frame.sec()), frame.width(), frame.height(),
                    frame.jpeg().length, dataUrl));
        }
        return refs;
    }

    private static BigDecimal dec(double value) {
        return BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
