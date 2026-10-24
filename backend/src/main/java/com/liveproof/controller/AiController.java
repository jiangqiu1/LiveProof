package com.liveproof.controller;

import com.liveproof.dto.AiCandidateResponse;
import com.liveproof.dto.AiReviewResponse;
import com.liveproof.dto.AiStatusDto;
import com.liveproof.enums.SubtitleSource;
import com.liveproof.exception.BadRequestException;
import com.liveproof.service.AiCandidateService;
import com.liveproof.service.AiReviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 辅助核验接口。
 *
 * <p>端点都不写库：核验结论仍由人工通过 PATCH /api/cases/{id} 保存；
 * 候选事项也要经 {@code POST /api/cases/from-candidates} 由用户显式确认后才落库。
 * 客户端只能选择「用哪份字幕」，无法指定服务器路径或视频。
 */
@RestController
@RequestMapping("/api")
public class AiController {

    private final AiReviewService aiReviewService;
    private final AiCandidateService aiCandidateService;

    public AiController(AiReviewService aiReviewService, AiCandidateService aiCandidateService) {
        this.aiReviewService = aiReviewService;
        this.aiCandidateService = aiCandidateService;
    }

    /** 能力状态：只读，不触发模型调用（前端据此展示"未配置"而不是发起请求）。 */
    @GetMapping("/ai/status")
    public AiStatusDto status() {
        return aiReviewService.status();
    }

    /**
     * 对单条用例发起一次 AI 分析。每次调用都会真实请求模型，前端只在用户点击时触发。
     *
     * @param subtitleSource 证据定位用的字幕来源：{@code local}（默认，本地 SRT）或 {@code cloud}
     *                       （保利威智能字幕）。其它取值一律 400，不做兜底猜测。
     */
    @PostMapping("/cases/{id}/ai-review")
    public AiReviewResponse review(@PathVariable("id") String id,
                                   @RequestParam(name = "subtitleSource", required = false) String subtitleSource) {
        return aiReviewService.review(id, parseSubtitleSource(subtitleSource));
    }

    /**
     * 从素材字幕生成候选核验事项。
     *
     * <p>只读：返回的是候选清单，不写库。用户在前端勾选、编辑后走
     * {@code POST /api/cases/from-candidates} 才落库。
     *
     * @param subtitleSource {@code auto}（默认，本地优先、没有就试云端）、{@code local}、{@code cloud}
     */
    @PostMapping("/ai/case-candidates")
    public AiCandidateResponse candidates(
            @RequestParam(name = "materialId", required = false) String materialId,
            @RequestParam(name = "subtitleSource", required = false) String subtitleSource) {
        return aiCandidateService.generate(materialId, subtitleSource);
    }

    /** 未传按 local（与接入云端前的行为一致）；非法值直接 400 并列出允许值。 */
    private static SubtitleSource parseSubtitleSource(String raw) {
        if (raw == null || raw.isBlank()) {
            return SubtitleSource.LOCAL;
        }
        try {
            return SubtitleSource.fromValue(raw);
        } catch (IllegalArgumentException e) {
            throw BadRequestException.invalidParameter(e.getMessage());
        }
    }
}
