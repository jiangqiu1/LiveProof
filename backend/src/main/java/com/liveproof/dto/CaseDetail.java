package com.liveproof.dto;

import com.liveproof.enums.AnswerStatus;
import com.liveproof.enums.EvidenceSufficiency;
import com.liveproof.enums.EvidenceType;
import com.liveproof.enums.ReviewStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用例详情。
 *
 * @param frames               关键帧路径列表（旧数据可能为空数组）
 * @param timePoints           人工标记的关联时间点（秒，升序）；未标记为空数组，与 startSec/endSec 无关
 * @param legacyEvidenceStatus 旧模型字段，只读；前端不得据此推导新四字段
 */
public record CaseDetail(
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
        String legacyEvidenceStatus,
        String reasoning,
        String limitations,
        List<BigDecimal> timePoints,
        List<String> frames,
        Boolean followUpRequired,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
