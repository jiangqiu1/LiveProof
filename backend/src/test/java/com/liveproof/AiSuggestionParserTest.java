package com.liveproof;

import com.liveproof.exception.ApiException;
import com.liveproof.ai.AiSuggestionParser;
import com.liveproof.dto.AiSuggestion;
import com.liveproof.dto.SubtitleCue;
import com.liveproof.enums.AnswerStatus;
import com.liveproof.enums.EvidenceSufficiency;
import com.liveproof.enums.EvidenceType;
import com.liveproof.service.EvidenceContextService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型输出解析与安全规范化的单元测试（不连库、不连模型）。
 */
class AiSuggestionParserTest {

    private final AiSuggestionParser parser = new AiSuggestionParser(new com.fasterxml.jackson.databind.ObjectMapper());

    /** 素材：一条字幕 20.031-25.291，一帧在 22.66；时长 106.47。 */
    private EvidenceContextService.Prepared prepared() {
        return new EvidenceContextService.Prepared(
                "local", "candidateflow-demo.mp4", 106.47, "candidateflow-demo.srt", 12,
                List.of(new SubtitleCue(3, 20.031, 25.291, "现在导入一份 CSV 文件，文件中包含 3 条候选人记录。")),
                Set.of(), false, List.of(22.66), null);
    }

    private static final String VALID = """
            {
              "summary": "字幕与画面都显示导入流程完成",
              "answerStatus": "answered",
              "evidenceType": "operational_evidence",
              "evidenceSufficiency": "sufficient",
              "reasoning": "00:20 起导入 CSV，00:45 显示成功 3 条",
              "limitations": "没有展示失败分支",
              "evidenceItems": [
                {"startSec": 20.031, "endSec": 25.291, "subtitle": "现在导入一份 CSV 文件",
                 "observation": "导入弹窗已打开", "supports": "确实发起了导入",
                 "cannotProve": "还不能证明导入成功", "frameSec": 22.66}
              ],
              "suggestedTimePoints": [22.66, 45.36],
              "unresolvedQuestions": ["失败记录如何展示？"]
            }
            """;

    @Test
    @DisplayName("合法 JSON：枚举正确解析，时间点对齐到材料")
    void parsesValidJson() {
        AiSuggestionParser.ParseResult result = parser.parse(VALID, prepared(), new BigDecimal("20.862"),
                new BigDecimal("46.906"));
        AiSuggestion suggestion = result.suggestion();
        assertThat(suggestion.answerStatus()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(suggestion.evidenceType()).isEqualTo(EvidenceType.OPERATIONAL_EVIDENCE);
        assertThat(suggestion.evidenceSufficiency()).isEqualTo(EvidenceSufficiency.SUFFICIENT);
        assertThat(suggestion.evidenceItems()).hasSize(1);
        assertThat(suggestion.evidenceItems().get(0).frameSec()).isEqualByComparingTo("22.66");
        // 45.36 附近没有字幕或关键帧，应被丢弃并记录说明
        assertThat(suggestion.suggestedTimePoints()).hasSize(1);
        assertThat(suggestion.suggestedTimePoints().get(0)).isEqualByComparingTo("22.66");
        assertThat(result.notes()).anyMatch(note -> note.contains("已忽略"));
    }

    @Test
    @DisplayName("```json 包裹与字符串数字都能容忍")
    void toleratesFenceAndStringNumbers() {
        String fenced = "```json\n" + VALID.replace("\"startSec\": 20.031", "\"startSec\": \"20.03\"") + "\n```";
        AiSuggestionParser.ParseResult result = parser.parse(fenced, prepared(), new BigDecimal("20.862"),
                new BigDecimal("46.906"));
        assertThat(result.suggestion().evidenceItems()).hasSize(1);
        assertThat(result.suggestion().evidenceItems().get(0).startSec()).isEqualByComparingTo("20.03");
    }

    @Test
    @DisplayName("非法枚举取值必须被拒绝，不做猜测性映射")
    void rejectsInvalidEnum() {
        String json = VALID.replace("\"answerStatus\": \"answered\"", "\"answerStatus\": \"probably_yes\"");
        assertThatThrownBy(() -> parser.parse(json, prepared(), null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("answerStatus")
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("AI_INVALID_RESPONSE");
    }

    @Test
    @DisplayName("缺少核心字段必须拒绝")
    void rejectsMissingEnum() {
        String json = VALID.replace("\"evidenceType\": \"operational_evidence\",", "");
        assertThatThrownBy(() -> parser.parse(json, prepared(), null, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("AI_INVALID_RESPONSE");
    }

    @Test
    @DisplayName("非 JSON 文本必须拒绝")
    void rejectsNonJson() {
        assertThatThrownBy(() -> parser.parse("我觉得这个视频挺好的", prepared(), null, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("AI_INVALID_RESPONSE");
    }

    @Test
    @DisplayName("证据区间与材料对不上、或越界时被丢弃而不是写进结果")
    void dropsUnverifiableEvidence() {
        String json = """
                {
                  "summary": "s", "answerStatus": "answered", "evidenceType": "operational_evidence",
                  "evidenceSufficiency": "sufficient", "reasoning": "r", "limitations": "l",
                  "evidenceItems": [
                    {"startSec": 5, "endSec": 9, "subtitle": "编造的片段", "observation": null,
                     "supports": "x", "cannotProve": "y", "frameSec": null},
                    {"startSec": 200, "endSec": 210, "subtitle": null, "observation": null,
                     "supports": "x", "cannotProve": "y", "frameSec": null},
                    {"startSec": 20.031, "endSec": 25.291, "subtitle": "导入 CSV", "observation": null,
                     "supports": "x", "cannotProve": "y", "frameSec": 22.66}
                  ],
                  "suggestedTimePoints": [999],
                  "unresolvedQuestions": []
                }
                """;
        AiSuggestionParser.ParseResult result = parser.parse(json, prepared(), new BigDecimal("20.862"),
                new BigDecimal("46.906"));
        assertThat(result.suggestion().evidenceItems()).hasSize(1);
        assertThat(result.suggestion().suggestedTimePoints()).isEmpty();
        assertThat(result.notes()).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("时间区间位于原时间窗之外时，outsideWindow 由服务端判定为 true")
    void marksOutsideWindowByServer() {
        EvidenceContextService.Prepared withOutside = new EvidenceContextService.Prepared(
                "local", "candidateflow-demo.mp4", 106.47, "candidateflow-demo.srt", 12,
                List.of(new SubtitleCue(3, 20.031, 25.291, "现在导入一份 CSV 文件"),
                        new SubtitleCue(8, 62.683, 69.153, "重复候选人识别已启用")),
                Set.of(8), false, List.of(22.66, 65.0), null);
        String json = VALID.replace("\"startSec\": 20.031, \"endSec\": 25.291",
                        "\"startSec\": 62.683, \"endSec\": 69.0")
                .replace("\"frameSec\": 22.66", "\"frameSec\": 65.0")
                .replace("\"suggestedTimePoints\": [22.66, 45.36]", "\"suggestedTimePoints\": [65.0]");
        AiSuggestionParser.ParseResult result = parser.parse(json, withOutside,
                new BigDecimal("20.862"), new BigDecimal("46.906"));
        assertThat(result.suggestion().evidenceItems()).hasSize(1);
        assertThat(result.suggestion().evidenceItems().get(0).outsideWindow()).isTrue();
        assertThat(result.suggestion().suggestedTimePoints()).hasSize(1);
    }

    @Test
    @DisplayName("模型漏写逐条说明与局限时，明确标注缺失并回报，而不是静默留白")
    void flagsMissingNarrativeFields() {
        String json = """
                {
                  "summary": "s", "answerStatus": "answered", "evidenceType": "operational_evidence",
                  "evidenceSufficiency": "sufficient", "reasoning": "整体依据",
                  "evidenceItems": [
                    {"startSec": 20.031, "endSec": 25.291, "subtitle": "导入 CSV", "observation": null,
                     "frameSec": 22.66}
                  ],
                  "suggestedTimePoints": [22.66],
                  "unresolvedQuestions": []
                }
                """;
        AiSuggestionParser.ParseResult result = parser.parse(json, prepared(),
                new BigDecimal("20.862"), new BigDecimal("46.906"));
        AiSuggestion suggestion = result.suggestion();
        // 兜底文案必须自带「缺了什么」的语义，避免被当成已核对结论
        assertThat(suggestion.limitations()).contains("未说明局限");
        assertThat(suggestion.evidenceItems().get(0).supports()).contains("未说明");
        assertThat(suggestion.evidenceItems().get(0).cannotProve()).contains("未说明");
        assertThat(result.notes()).anyMatch(note -> note.contains("缺少说明字段"));
        assertThat(result.notes()).anyMatch(note -> note.contains("limitations"));
    }
}
