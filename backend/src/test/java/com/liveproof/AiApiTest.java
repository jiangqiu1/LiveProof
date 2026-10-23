package com.liveproof;

import com.liveproof.ai.AiClient;
import com.liveproof.ai.AiCompletion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 辅助核验接口测试。
 *
 * <p>模型调用被替换为桩（唯一允许 mock 的位置）：这样断言的是<b>我们自己的</b>
 * 提示词组装、结构化校验与"不写库"约束，而不是模型质量。
 */
class AiApiTest extends ApiTestBase {

    private static final String CASE_ID = "cf-import-3-records";

    @MockitoBean
    private AiClient aiClient;

    @BeforeEach
    void stubClient() {
        when(aiClient.isConfigured()).thenReturn(true);
        when(aiClient.model()).thenReturn("deepseek-flash");
        when(aiClient.endpointHost()).thenReturn("api.deepseek.com");
    }

    private static final String MODEL_JSON = """
            {
              "summary": "字幕显示了导入动作与成功提示，但没有展示失败记录的处理",
              "answerStatus": "answered",
              "evidenceType": "operational_evidence",
              "evidenceSufficiency": "insufficient",
              "reasoning": "00:20 发起 CSV 导入，00:45 提示成功 3 条失败 0 条",
              "limitations": "没有展示失败记录；未验证更大数据量",
              "evidenceItems": [
                {"startSec": 20.031, "endSec": 25.291, "subtitle": "现在导入一份 CSV 文件，文件中包含 3 条候选人记录。",
                 "observation": null, "supports": "确实发起了 3 条记录的导入",
                 "cannotProve": "不能证明写入成功", "frameSec": null},
                {"startSec": 45.356, "endSec": 51.496, "subtitle": "导入完成：成功 3 条，失败 0 条",
                 "observation": null, "supports": "界面回显成功 3 条",
                 "cannotProve": "不能证明数据真实入库", "frameSec": null}
              ],
              "suggestedTimePoints": [22.66, 48.43, 999],
              "unresolvedQuestions": ["失败记录是否可导出？"]
            }
            """;

    private Map<String, Object> snapshot() {
        return jdbcTemplate.queryForMap("""
                SELECT review_status, answer_status, evidence_type, evidence_sufficiency,
                       reasoning, limitations, time_points
                  FROM cases WHERE id = ?
                """, CASE_ID);
    }

    @Test
    @DisplayName("已配置时返回结构化建议，且不改动数据库任何字段")
    void returnsSuggestionWithoutWritingDatabase() throws Exception {
        when(aiClient.complete(anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(new AiCompletion(MODEL_JSON, "deepseek-flash", 900, 320));
        Map<String, Object> before = snapshot();

        mockMvc.perform(post("/api/cases/" + CASE_ID + "/ai-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(CASE_ID))
                .andExpect(jsonPath("$.suggestion.answerStatus").value("answered"))
                .andExpect(jsonPath("$.suggestion.evidenceType").value("operational_evidence"))
                .andExpect(jsonPath("$.suggestion.evidenceSufficiency").value("insufficient"))
                .andExpect(jsonPath("$.suggestion.evidenceItems.length()").value(2))
                .andExpect(jsonPath("$.suggestion.evidenceItems[0].outsideWindow").value(false))
                // 999 越界必须被丢弃，只剩两个与字幕对齐的时间点
                .andExpect(jsonPath("$.suggestion.suggestedTimePoints.length()").value(2))
                .andExpect(jsonPath("$.normalizationNotes").isArray())
                .andExpect(jsonPath("$.context.visionUsed").value(false))
                .andExpect(jsonPath("$.context.model").value("deepseek-flash"))
                // 建议里绝不出现 reviewStatus
                .andExpect(jsonPath("$.suggestion.reviewStatus").doesNotExist());

        assertThat(snapshot()).isEqualTo(before);
        // 测试环境没有 FFmpeg，因此提交给模型的图片列表必须为空
        verify(aiClient).complete(anyString(), anyString(), eq(List.of()), eq(true));
    }

    @Test
    @DisplayName("模型返回非法枚举时返回 422，数据库不变")
    void invalidEnumIsRejected() throws Exception {
        String bad = MODEL_JSON.replace("\"answerStatus\": \"answered\"", "\"answerStatus\": \"maybe\"");
        when(aiClient.complete(anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(new AiCompletion(bad, "deepseek-flash", 900, 320));
        Map<String, Object> before = snapshot();

        mockMvc.perform(post("/api/cases/" + CASE_ID + "/ai-review"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("AI_INVALID_RESPONSE"));

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("模型调用失败时返回 502，数据库不变")
    void upstreamFailureKeepsDatabase() throws Exception {
        when(aiClient.complete(anyString(), anyString(), any(), anyBoolean()))
                .thenThrow(com.liveproof.exception.AiErrors.upstream("服务端返回 500"));
        Map<String, Object> before = snapshot();

        mockMvc.perform(post("/api/cases/" + CASE_ID + "/ai-review"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("AI_UPSTREAM_ERROR"));

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("用例不存在返回 404")
    void unknownCaseIs404() throws Exception {
        mockMvc.perform(post("/api/cases/cf-not-exist/ai-review"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CASE_NOT_FOUND"));
    }

    @Test
    @DisplayName("状态接口只读，不触发模型调用")
    void statusDoesNotCallModel() throws Exception {
        mockMvc.perform(get("/api/ai/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.model").value("deepseek-flash"))
                .andExpect(jsonPath("$.ffmpegAvailable").value(false));
        verify(aiClient, org.mockito.Mockito.never()).complete(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("默认按本地字幕定位，并在上下文中写明来源")
    void defaultsToLocalSubtitleSource() throws Exception {
        when(aiClient.complete(anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(new AiCompletion(MODEL_JSON, "deepseek-flash", 900, 320));

        mockMvc.perform(post("/api/cases/" + CASE_ID + "/ai-review")
                        .param("subtitleSource", "local"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context.subtitleSource").value("local"))
                .andExpect(jsonPath("$.context.subtitleFile").value("candidateflow-demo.srt"));
    }

    @Test
    @DisplayName("subtitleSource 取值非法时返回 400，不调用模型")
    void rejectsInvalidSubtitleSource() throws Exception {
        mockMvc.perform(post("/api/cases/" + CASE_ID + "/ai-review")
                        .param("subtitleSource", "polyv"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
        verify(aiClient, org.mockito.Mockito.never()).complete(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("要求云端字幕但没有就绪任务时返回 409，不退化为无字幕分析")
    void cloudSourceWithoutReadyTaskIsRejected() throws Exception {
        // 测试库没有该视频的智能字幕任务登记：必须明确拒绝，不能拿空字幕去问模型
        mockMvc.perform(post("/api/cases/" + CASE_ID + "/ai-review")
                        .param("subtitleSource", "cloud"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SMART_SUBTITLE_NOT_READY"));
        verify(aiClient, org.mockito.Mockito.never()).complete(anyString(), anyString(), any(), anyBoolean());
    }
}
