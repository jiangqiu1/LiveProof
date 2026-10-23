package com.liveproof;

import com.liveproof.enums.AnswerStatus;
import com.liveproof.enums.EnumSupport;
import com.liveproof.enums.EvidenceSufficiency;
import com.liveproof.enums.EvidenceType;
import com.liveproof.enums.ReviewStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 枚举解析单元测试。重点：非法值必须抛异常，不得兜底成任何默认值。
 */
class EnumParsingTest {

    @Test
    @DisplayName("小写蛇形取值可解析")
    void parsesSnakeCase() {
        assertThat(EnumSupport.parse(ReviewStatus.class, "in_review", "reviewStatus"))
                .isEqualTo(ReviewStatus.IN_REVIEW);
        assertThat(EnumSupport.parse(EvidenceType.class, "operational_evidence", "evidenceType"))
                .isEqualTo(EvidenceType.OPERATIONAL_EVIDENCE);
        assertThat(EnumSupport.parse(EvidenceSufficiency.class, "not_checked", "evidenceSufficiency"))
                .isEqualTo(EvidenceSufficiency.NOT_CHECKED);
    }

    @Test
    @DisplayName("unknown 是合法证据形态，必须允许")
    void unknownIsLegal() {
        assertThat(EnumSupport.parse(EvidenceType.class, "unknown", "evidenceType"))
                .isEqualTo(EvidenceType.UNKNOWN);
    }

    @Test
    @DisplayName("旧词汇 UNANSWERED 不属于新契约，必须拒绝而不是静默转换")
    void rejectsLegacyVocabulary() {
        assertThatThrownBy(() -> EnumSupport.parse(AnswerStatus.class, "UNANSWERED", "answerStatus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not_found");
        assertThatThrownBy(() -> EnumSupport.parse(EvidenceType.class, "EVIDENCE_PARTIAL", "evidenceType"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("非法取值抛出异常且提示允许值")
    void rejectsIllegalValue() {
        assertThatThrownBy(() -> EnumSupport.parse(ReviewStatus.class, "verified", "reviewStatus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unreviewed")
                .hasMessageContaining("reviewed");
    }

    @Test
    @DisplayName("空串不是合法取值")
    void rejectsBlank() {
        assertThatThrownBy(() -> EnumSupport.parse(AnswerStatus.class, "  ", "answerStatus"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null 表示未提供")
    void nullMeansAbsent() {
        assertThat(EnumSupport.parse(AnswerStatus.class, null, "answerStatus")).isNull();
    }
}
