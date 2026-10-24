package com.liveproof;

import com.liveproof.ai.AiClient;
import com.liveproof.ai.AiCompletion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 候选事项生成与确认入库的接口测试。
 *
 * <p>模型调用被替换为桩：断言的是我们自己的提示词组装、时间对齐、去重与「不写库 / 不写结论」约束。
 *
 * <p>这里的两条红线各有一个测试盯着：
 * <ul>
 *   <li>生成接口<b>绝不写库</b>（候选只是清单）</li>
 *   <li>入库接口写进去的一律是<b>尚未核验</b>（reviewStatus=unreviewed、answerStatus=NULL）</li>
 * </ul>
 */
class AiCandidateApiTest extends ApiTestBase {

    private static final String MATERIAL_ID = "mat_builtin_demo";

    /** 20.031-25.291 与 45.356-51.496 是 candidateflow-demo.srt 里真实存在的两条 cue。 */
    private static final String MODEL_JSON = """
            {
              "candidates": [
                {
                  "question": "是否能把房间容量上限改到 5000？",
                  "claim": "视频称支持把房间容量上限调整到 5000",
                  "startSec": 40.031,
                  "endSec": 44.0,
                  "quote": "最多可以改成五千人"
                },
                {
                  "question": "一次导入能否写入 3 条候选人记录？",
                  "claim": "视频演示了导入 3 条候选人记录",
                  "startSec": 20.031,
                  "endSec": 25.291,
                  "quote": "现在导入一份 CSV 文件，文件中包含 3 条候选人记录。"
                }
              ]
            }
            """;

    @MockitoBean
    private AiClient aiClient;

    @BeforeEach
    void stubClient() {
        // @MockitoBean 不像旧的 @MockBean 那样在每个测试方法后自动重置，
        // 同一类里的调用记录会累积，verify(never()) 会误判成被调用过
        Mockito.reset(aiClient);
        when(aiClient.isConfigured()).thenReturn(true);
        when(aiClient.model()).thenReturn("deepseek-flash");
        when(aiClient.endpointHost()).thenReturn("api.deepseek.com");
    }

    private int caseCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cases", Integer.class);
        return count == null ? 0 : count;
    }

    private void stubModel(String json) {
        when(aiClient.complete(anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(new AiCompletion(json, "deepseek-flash", 800, 260));
    }

    // ------------------------------------------------------------------ 生成

    @Test
    @DisplayName("从字幕生成候选，按时间升序返回且不写库")
    void generatesCandidatesWithoutWritingDatabase() throws Exception {
        stubModel(MODEL_JSON);
        int before = caseCount();

        mockMvc.perform(post("/api/ai/case-candidates").param("materialId", MATERIAL_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.materialId").value(MATERIAL_ID))
                .andExpect(jsonPath("$.subtitleSource").value("local"))
                .andExpect(jsonPath("$.subtitleFile").value("candidateflow-demo.srt"))
                .andExpect(jsonPath("$.cuesUsed").value(12))
                .andExpect(jsonPath("$.candidates.length()").value(2))
                // 升序：20.031 在 40.031 之前
                .andExpect(jsonPath("$.candidates[0].startSec").value(20.03))
                .andExpect(jsonPath("$.candidates[0].question").value("一次导入能否写入 3 条候选人记录？"))
                .andExpect(jsonPath("$.candidates[0].duplicate").value(false))
                // 引用原文必须来自真实字幕
                .andExpect(jsonPath("$.candidates[0].quote").value(containsString("3 条候选人记录")))
                .andExpect(jsonPath("$.duplicateCount").value(0))
                // 候选里绝不能出现任何核验结论字段
                .andExpect(jsonPath("$.candidates[0].reviewStatus").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].answerStatus").doesNotExist());

        assertThat(caseCount()).isEqualTo(before);
        // 候选提取只看文本，不提交画面
        verify(aiClient).complete(contains("选题助手"), anyString(), eq(List.of()), eq(true));
    }

    @Test
    @DisplayName("时间对不上任何字幕的候选被丢弃，并在 notes 里说明")
    void dropsCandidatesOutsideSubtitle() throws Exception {
        stubModel("""
                {"candidates": [
                  {"question": "编造时间的候选", "claim": "视频里说过", "startSec": 900, "endSec": 950,
                   "quote": "并不存在这句话"}
                ]}
                """);

        mockMvc.perform(post("/api/ai/case-candidates").param("materialId", MATERIAL_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(0))
                .andExpect(jsonPath("$.notes[0]").value(containsString("已忽略")));
    }

    @Test
    @DisplayName("模型确实没找出承诺时返回 200 + 空数组，不是错误")
    void emptyCandidatesIsSuccessNotError() throws Exception {
        stubModel("{\"candidates\": []}");
        int before = caseCount();

        mockMvc.perform(post("/api/ai/case-candidates").param("materialId", MATERIAL_ID))
                // 「字幕有、模型也答了、但没有可核验的承诺」必须是 200 + 空清单：
                // 前端据此显示「没有候选」，与「无可用字幕 409」「生成失败 5xx」分开
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(0))
                .andExpect(jsonPath("$.duplicateCount").value(0))
                // 字幕本身是读到了的，只是没有候选
                .andExpect(jsonPath("$.cuesUsed").value(12))
                .andExpect(jsonPath("$.subtitleFile").value("candidateflow-demo.srt"));

        assertThat(caseCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("模型改写的引用原文会被替换成真实字幕")
    void rewritesFabricatedQuote() throws Exception {
        stubModel("""
                {"candidates": [
                  {"question": "能否导出失败记录？", "claim": "视频称失败记录可导出",
                   "startSec": 20.031, "endSec": 25.291, "quote": "随便写一句字幕里没有的话"}
                ]}
                """);

        mockMvc.perform(post("/api/ai/case-candidates").param("materialId", MATERIAL_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].quote").value(containsString("3 条候选人记录")))
                .andExpect(jsonPath("$.notes[0]").value(containsString("引用原文与字幕不一致")));
    }

    @Test
    @DisplayName("素材没有可用字幕时返回 409，不当成「没有候选」")
    void missingSubtitleIsConflict() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO materials (id, storage, polyv_vid, file_name, original_name, duration_sec, status, builtin)
                VALUES ('mat_probe_nosub', 'local', NULL, 'probe-empty.mp4', 'probe-empty.mp4', 60.000, 'ready', 0)
                """);

        mockMvc.perform(post("/api/ai/case-candidates").param("materialId", "mat_probe_nosub"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SUBTITLE_UNAVAILABLE"));

        // 没有字幕就不该去问模型
        verify(aiClient, never()).complete(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("明确指定本地字幕但没有时，提示上传 SRT")
    void localSourceWithoutSrtExplainsNextStep() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO materials (id, storage, polyv_vid, file_name, original_name, duration_sec, status, builtin)
                VALUES ('mat_probe_nosub', 'local', NULL, 'probe-empty.mp4', 'probe-empty.mp4', 60.000, 'ready', 0)
                """);

        mockMvc.perform(post("/api/ai/case-candidates")
                        .param("materialId", "mat_probe_nosub")
                        .param("subtitleSource", "local"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SUBTITLE_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.message").value(containsString("上传")));
    }

    @Test
    @DisplayName("要求云端字幕但没有就绪任务时保留 409 SMART_SUBTITLE_NOT_READY")
    void cloudSourceWithoutReadyTaskKeepsCode() throws Exception {
        mockMvc.perform(post("/api/ai/case-candidates")
                        .param("materialId", MATERIAL_ID)
                        .param("subtitleSource", "cloud"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SMART_SUBTITLE_NOT_READY"));
        verify(aiClient, never()).complete(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("subtitleSource 取值非法时返回 400")
    void invalidSubtitleSourceIs400() throws Exception {
        mockMvc.perform(post("/api/ai/case-candidates")
                        .param("materialId", MATERIAL_ID)
                        .param("subtitleSource", "srt"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("素材不存在返回 404")
    void unknownMaterialIs404() throws Exception {
        mockMvc.perform(post("/api/ai/case-candidates").param("materialId", "mat-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("模型返回非法 JSON 时返回 422")
    void invalidModelJsonIs422() throws Exception {
        stubModel("这不是 JSON");
        mockMvc.perform(post("/api/ai/case-candidates").param("materialId", MATERIAL_ID))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("AI_INVALID_RESPONSE"));
    }

    @Test
    @DisplayName("同一批里重复的问题被标记，并指向批内第几条")
    void deduplicatesWithinBatch() throws Exception {
        stubModel("""
                {"candidates": [
                  {"question": "能否导入 3 条候选人记录？", "claim": "视频演示导入 3 条",
                   "startSec": 20.031, "endSec": 25.291, "quote": "包含 3 条候选人记录"},
                  {"question": "能否导入 3 条候选人记录", "claim": "视频演示导入 3 条",
                   "startSec": 45.356, "endSec": 51.496, "quote": "导入完成：成功 3 条"}
                ]}
                """);

        mockMvc.perform(post("/api/ai/case-candidates").param("materialId", MATERIAL_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(2))
                .andExpect(jsonPath("$.candidates[0].duplicate").value(false))
                .andExpect(jsonPath("$.candidates[1].duplicate").value(true))
                .andExpect(jsonPath("$.candidates[1].duplicateOfIndex").value(1))
                .andExpect(jsonPath("$.duplicateCount").value(1));
    }

    @Test
    @DisplayName("与库内已有事项重复时，标出重复的 case id")
    void deduplicatesAgainstExistingCases() throws Exception {
        String body = """
                {"materialId":"%s","question":"是否支持把房间容量上限改到 5000？",
                 "claim":"支持将房间容量上限调整到 5000","startSec":40,"endSec":50}
                """.formatted(MATERIAL_ID);
        String response = mockMvc.perform(post("/api/cases")
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String caseId = objectMapper.readTree(response).path("id").asText();

        stubModel("""
                {"candidates": [
                  {"question": "是否支持把房间容量上限改到 5000", "claim": "支持调到 5000",
                   "startSec": 40.031, "endSec": 44.0, "quote": "最多可以改成五千人"}
                ]}
                """);

        mockMvc.perform(post("/api/ai/case-candidates").param("materialId", MATERIAL_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].duplicate").value(true))
                .andExpect(jsonPath("$.candidates[0].duplicateOf").value(caseId));
    }

    // ------------------------------------------------------------------ 确认入库

    @Test
    @DisplayName("确认入库：来源记为 ai，核验字段一律是尚未核验")
    void createsBatchAsUnreviewed() throws Exception {
        String body = """
                {"materialId":"%s","items":[
                  {"question":"候选人导入支持 3 条记录吗？","claim":"演示导入 3 条记录",
                   "startSec":20.031,"endSec":25.291},
                  {"question":"导入完成后会提示成功条数吗？","claim":"演示提示成功 3 条失败 0 条",
                   "startSec":45.356,"endSec":51.496}
                ]}
                """.formatted(MATERIAL_ID);

        mockMvc.perform(post("/api/cases/from-candidates").contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.createdCount").value(2))
                .andExpect(jsonPath("$.source").value("ai"))
                .andExpect(jsonPath("$.created[0].source").value("ai"))
                .andExpect(jsonPath("$.created[0].reviewStatus").value("unreviewed"))
                // 尚未核验：answerStatus 必须是 null，不是 not_found
                .andExpect(jsonPath("$.created[0].answerStatus").doesNotExist())
                .andExpect(jsonPath("$.created[0].evidenceType").value("unknown"))
                .andExpect(jsonPath("$.created[0].evidenceSufficiency").value("not_checked"));

        Integer written = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM cases
                 WHERE material_id = ? AND source = 'ai'
                   AND review_status = 'unreviewed' AND answer_status IS NULL
                """, Integer.class, MATERIAL_ID);
        assertThat(written).isEqualTo(2);
    }

    @Test
    @DisplayName("任一条不合法就整批拒绝，一条都不写入")
    void rejectsWholeBatchWhenOneItemInvalid() throws Exception {
        int before = caseCount();
        String body = """
                {"materialId":"%s","items":[
                  {"question":"合法的一条","claim":"演示里说过","startSec":20.031,"endSec":25.291},
                  {"question":"","claim":"缺问题","startSec":45.356,"endSec":51.496}
                ]}
                """.formatted(MATERIAL_ID);

        mockMvc.perform(post("/api/cases/from-candidates").contentType("application/json").content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CASE_CANDIDATE_INVALID"))
                .andExpect(jsonPath("$.error.message").value(containsString("第 2 条")));

        assertThat(caseCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("时间区间颠倒时整批拒绝")
    void rejectsReversedRange() throws Exception {
        String body = """
                {"materialId":"%s","items":[
                  {"question":"时间颠倒","claim":"演示里说过","startSec":50,"endSec":40}
                ]}
                """.formatted(MATERIAL_ID);

        mockMvc.perform(post("/api/cases/from-candidates").contentType("application/json").content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value(containsString("开始秒不能大于结束秒")));
    }

    @Test
    @DisplayName("空清单与超量清单返回 400")
    void rejectsEmptyAndOversizedBatch() throws Exception {
        mockMvc.perform(post("/api/cases/from-candidates").contentType("application/json")
                        .content("{\"materialId\":\"%s\",\"items\":[]}".formatted(MATERIAL_ID)))
                .andExpect(status().isBadRequest());

        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            if (i > 0) {
                items.append(',');
            }
            items.append("{\"question\":\"问题").append(i).append("\",\"claim\":\"承诺").append(i).append("\"}");
        }
        mockMvc.perform(post("/api/cases/from-candidates").contentType("application/json")
                        .content("{\"materialId\":\"%s\",\"items\":[%s]}".formatted(MATERIAL_ID, items)))
                .andExpect(status().isBadRequest());
    }
}
