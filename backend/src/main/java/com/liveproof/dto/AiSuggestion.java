package com.liveproof.dto;

import com.liveproof.enums.AnswerStatus;
import com.liveproof.enums.EvidenceSufficiency;
import com.liveproof.enums.EvidenceType;

import java.math.BigDecimal;
import java.util.List;

/**
 * 模型给出的核验建议。
 *
 * <p><b>建议不是结论</b>：本对象不包含 reviewStatus，也不包含任何「已确认」语义。
 * 是否采纳由人工在前端逐项选择，采纳后仍需走原有 PATCH 保存。
 */
public record AiSuggestion(
        String summary,
        AnswerStatus answerStatus,
        EvidenceType evidenceType,
        EvidenceSufficiency evidenceSufficiency,
        String reasoning,
        String limitations,
        List<AiEvidenceItem> evidenceItems,
        List<BigDecimal> suggestedTimePoints,
        List<String> unresolvedQuestions
) {
}
