package com.liveproof;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 改判接口测试。
 *
 * <p>本类最重要的断言是<b>四字段独立性</b>：改一个字段时其余三个必须保持原值。
 * 这是产品核心语义，一旦被自动覆盖，核验结论就会失真。
 */
class CasePatchTest extends ApiTestBase {

    private static final String CASE_ID = "cf-capacity-5000";

    private JsonNode fetch(String id) throws Exception {
        String body = mockMvc.perform(get("/api/cases/" + id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    /** 把响应里的 timePoints 数组读成 double 列表，避免依赖 JSON 文本上的小数位写法。 */
    private static java.util.List<Double> timePointsOf(JsonNode node) {
        java.util.List<Double> list = new java.util.ArrayList<>();
        node.get("timePoints").forEach(item -> list.add(item.asDouble()));
        return list;
    }

    private JsonNode patchCase(String id, String json) throws Exception {
        String body = mockMvc.perform(patch("/api/cases/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    @Test
    @DisplayName("四个字段可分别保存并重新读取，互不覆盖")
    void fourFieldsAreIndependent() throws Exception {
        JsonNode before = fetch(CASE_ID);
        assertThat(before.get("answerStatus").asText()).isEqualTo("answered");
        assertThat(before.get("reviewStatus").asText()).isEqualTo("unreviewed");

        // 1) 只改证据形态
        JsonNode step1 = patchCase(CASE_ID, "{\"evidenceType\":\"verbal_claim\"}");
        assertThat(step1.get("evidenceType").asText()).isEqualTo("verbal_claim");
        assertThat(step1.get("answerStatus").asText()).isEqualTo("answered");
        assertThat(step1.get("reviewStatus").asText()).isEqualTo("unreviewed");
        assertThat(step1.get("evidenceSufficiency").asText()).isEqualTo("insufficient");

        // 2) 只改充分性，证据形态不得被重置
        JsonNode step2 = patchCase(CASE_ID, "{\"evidenceSufficiency\":\"not_checked\"}");
        assertThat(step2.get("evidenceSufficiency").asText()).isEqualTo("not_checked");
        assertThat(step2.get("evidenceType").asText()).isEqualTo("verbal_claim");

        // 3) 只改流程状态，两个证据维度不得变化
        JsonNode step3 = patchCase(CASE_ID, "{\"reviewStatus\":\"in_review\"}");
        assertThat(step3.get("reviewStatus").asText()).isEqualTo("in_review");
        assertThat(step3.get("evidenceType").asText()).isEqualTo("verbal_claim");
        assertThat(step3.get("evidenceSufficiency").asText()).isEqualTo("not_checked");

        // 4) 只改回答状态，流程状态不得被重置为 unreviewed
        JsonNode step4 = patchCase(CASE_ID, "{\"answerStatus\":\"partially_answered\"}");
        assertThat(step4.get("answerStatus").asText()).isEqualTo("partially_answered");
        assertThat(step4.get("reviewStatus").asText()).isEqualTo("in_review");

        // 5) 重新发起请求确认已持久化（不是回显入参）
        JsonNode reloaded = fetch(CASE_ID);
        assertThat(reloaded.get("reviewStatus").asText()).isEqualTo("in_review");
        assertThat(reloaded.get("answerStatus").asText()).isEqualTo("partially_answered");
        assertThat(reloaded.get("evidenceType").asText()).isEqualTo("verbal_claim");
        assertThat(reloaded.get("evidenceSufficiency").asText()).isEqualTo("not_checked");
    }

    @Test
    @DisplayName("一次提交四个字段同时生效")
    void patchAllFourAtOnce() throws Exception {
        JsonNode result = patchCase(CASE_ID, """
                {"reviewStatus":"reviewed","answerStatus":"not_found",
                 "evidenceType":"unknown","evidenceSufficiency":"not_checked"}
                """);
        assertThat(result.get("reviewStatus").asText()).isEqualTo("reviewed");
        assertThat(result.get("answerStatus").asText()).isEqualTo("not_found");
        assertThat(result.get("evidenceType").asText()).isEqualTo("unknown");
        assertThat(result.get("evidenceSufficiency").asText()).isEqualTo("not_checked");
    }

    @Test
    @DisplayName("显式传 null 等同于未提供，不清空其它字段")
    void explicitNullIsIgnored() throws Exception {
        JsonNode result = patchCase(CASE_ID, "{\"reviewStatus\":\"reviewed\",\"evidenceType\":null}");
        assertThat(result.get("reviewStatus").asText()).isEqualTo("reviewed");
        assertThat(result.get("evidenceType").asText()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("改判不改动旧列 evidence_status（旧数据保留）")
    void legacyColumnUntouched() throws Exception {
        JsonNode before = fetch(CASE_ID);
        String legacyBefore = before.get("legacyEvidenceStatus").asText();

        patchCase(CASE_ID, "{\"evidenceType\":\"operational_evidence\",\"evidenceSufficiency\":\"sufficient\"}");

        JsonNode after = fetch(CASE_ID);
        assertThat(after.get("legacyEvidenceStatus").asText()).isEqualTo(legacyBefore);
    }

    @Test
    @DisplayName("非法枚举取值返回 400，且不写入任何字段")
    void invalidEnumRejected() throws Exception {
        mockMvc.perform(patch("/api/cases/" + CASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"verified\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", org.hamcrest.Matchers.is("INVALID_JSON")))
                .andExpect(jsonPath("$.error.message", containsString("unreviewed")));

        assertThat(fetch(CASE_ID).get("reviewStatus").asText()).isEqualTo("unreviewed");
    }

    @Test
    @DisplayName("非法证据形态返回 400")
    void invalidEvidenceTypeRejected() throws Exception {
        mockMvc.perform(patch("/api/cases/" + CASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evidenceType\":\"EVIDENCE_PARTIAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", containsString("application/json")));
    }

    @Test
    @DisplayName("空请求体返回 400，不静默成功")
    void emptyPatchRejected() throws Exception {
        mockMvc.perform(patch("/api/cases/" + CASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", org.hamcrest.Matchers.is("NO_UPDATABLE_FIELD")));
    }

    @Test
    @DisplayName("不存在的用例返回 404")
    void unknownCase() throws Exception {
        mockMvc.perform(patch("/api/cases/nope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"reviewed\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", org.hamcrest.Matchers.is("CASE_NOT_FOUND")));
    }

    @Test
    @DisplayName("重复提交相同值仍返回 200（修正旧实现用 affectedRows 误判 404 的缺陷）")
    void sameValueTwice() throws Exception {
        patchCase(CASE_ID, "{\"answerStatus\":\"answered\"}");
        mockMvc.perform(patch("/api/cases/" + CASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answerStatus\":\"answered\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answerStatus", org.hamcrest.Matchers.is("answered")));
    }

    @Test
    @DisplayName("错误请求体不是合法 JSON 时返回 400")
    void malformedJson() throws Exception {
        mockMvc.perform(patch("/api/cases/" + CASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", org.hamcrest.Matchers.is("INVALID_JSON")));
    }

    // ---------------- 关联时间点（timePoints） ----------------

    @Test
    @DisplayName("关联时间点升序去重后落库，刷新仍存在，且不影响四个字段")
    void timePointsPersistAndNormalize() throws Exception {
        JsonNode result = patchCase(CASE_ID, "{\"timePoints\":[30.004,12.5,12.5,0]}");
        // 12.5 与 12.5 去重、30.004 归到两位小数、整体升序
        assertThat(timePointsOf(result)).containsExactly(0.0, 12.5, 30.0);
        // 四个字段保持基线
        assertThat(result.get("reviewStatus").asText()).isEqualTo("unreviewed");
        assertThat(result.get("answerStatus").asText()).isEqualTo("answered");
        assertThat(result.get("evidenceType").asText()).isEqualTo("unknown");
        assertThat(result.get("evidenceSufficiency").asText()).isEqualTo("insufficient");

        JsonNode reloaded = fetch(CASE_ID);
        assertThat(timePointsOf(reloaded)).containsExactly(0.0, 12.5, 30.0);

        // 未提供 timePoints 时改判不得清空已保存的时间点
        JsonNode after = patchCase(CASE_ID, "{\"reviewStatus\":\"reviewed\"}");
        assertThat(timePointsOf(after)).containsExactly(0.0, 12.5, 30.0);
    }

    @Test
    @DisplayName("传空数组清空时间点，与不传区分")
    void timePointsClearedByEmptyArray() throws Exception {
        patchCase(CASE_ID, "{\"timePoints\":[12.5]}");
        JsonNode cleared = patchCase(CASE_ID, "{\"timePoints\":[]}");
        assertThat(cleared.get("timePoints").isArray()).isTrue();
        assertThat(cleared.get("timePoints").size()).isZero();
        assertThat(fetch(CASE_ID).get("timePoints").size()).isZero();
    }

    @Test
    @DisplayName("时间点越界或含 null 返回 400，且不写入")
    void timePointsOutOfRangeRejected() throws Exception {
        for (String body : new String[]{"{\"timePoints\":[-0.5]}", "{\"timePoints\":[7201]}", "{\"timePoints\":[null]}"}) {
            mockMvc.perform(patch("/api/cases/" + CASE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code", org.hamcrest.Matchers.is("INVALID_PARAMETER")));
        }
        assertThat(fetch(CASE_ID).get("timePoints").size()).isZero();
    }

    @Test
    @DisplayName("时间点数量超过 20 个返回 400")
    void timePointsTooManyRejected() throws Exception {
        String body = "{\"timePoints\":" + java.util.stream.IntStream.range(0, 21)
                .mapToObj(String::valueOf).collect(java.util.stream.Collectors.joining(",", "[", "]")) + "}";
        mockMvc.perform(patch("/api/cases/" + CASE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", org.hamcrest.Matchers.is("INVALID_PARAMETER")));
    }
}
