package com.liveproof.dto;

import com.liveproof.enums.AnswerStatus;
import com.liveproof.enums.EvidenceSufficiency;
import com.liveproof.enums.EvidenceType;
import com.liveproof.enums.ReviewStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用例列表项。时间字段为 JSON number（BigDecimal 直出，不转字符串）。
 */
public record CaseListItem(
        String id,
        String ds,
        String question,
        String claim,
        String video,
        String materialId,
        String source,
        BigDecimal startSec,
        BigDecimal endSec,
        ReviewStatus reviewStatus,
        AnswerStatus answerStatus,
        EvidenceType evidenceType,
        EvidenceSufficiency evidenceSufficiency,
        Boolean followUpRequired,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
