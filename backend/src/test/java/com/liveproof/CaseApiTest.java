package com.liveproof;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.isA;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用例读取接口契约测试：数据来自真实数据库，时间字段必须是 JSON number。
 */
class CaseApiTest extends ApiTestBase {

    @Test
    @DisplayName("GET /api/datasets 返回数据集与真实计数")
    void datasets() throws Exception {
        mockMvc.perform(get("/api/datasets"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/json"))
                .andExpect(jsonPath("$[0].id", org.hamcrest.Matchers.is("cf")))
                .andExpect(jsonPath("$[0].count", org.hamcrest.Matchers.is(6)))
                .andExpect(jsonPath("$[0].label", notNullValue()));
    }

    @Test
    @DisplayName("GET /api/cases 时间字段为 number，四字段齐全")
    void listCases() throws Exception {
        mockMvc.perform(get("/api/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[*].startSec", everyItem(isA(Number.class))))
                .andExpect(jsonPath("$[*].endSec", everyItem(isA(Number.class))))
                .andExpect(jsonPath("$[0].reviewStatus", notNullValue()))
                // answerStatus 不在这里断言非空：V8 起「尚未核验」就是 null，
                // 由 unreviewedCaseHasNullAnswerStatus 单独守住这条语义
                .andExpect(jsonPath("$[0].evidenceType", notNullValue()))
                .andExpect(jsonPath("$[0].evidenceSufficiency", notNullValue()));
    }

    @Test
    @DisplayName("start_sec 的具体数值与库中 DECIMAL 一致，未变成字符串")
    void startSecIsNumeric() throws Exception {
        String body = mockMvc.perform(get("/api/cases/cf-capacity-5000"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
        org.assertj.core.api.Assertions.assertThat(node.get("startSec").isNumber()).isTrue();
        org.assertj.core.api.Assertions.assertThat(node.get("startSec").isTextual()).isFalse();
        org.assertj.core.api.Assertions.assertThat(node.get("startSec").doubleValue()).isEqualTo(53.684);
        org.assertj.core.api.Assertions.assertThat(node.get("endSec").doubleValue()).isEqualTo(61.533);
    }

    @Test
    @DisplayName("详情包含 reasoning / limitations / frames / legacyEvidenceStatus")
    void caseDetail() throws Exception {
        mockMvc.perform(get("/api/cases/cf-import-3-records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", org.hamcrest.Matchers.is("cf-import-3-records")))
                .andExpect(jsonPath("$.video", org.hamcrest.Matchers.is("candidateflow-demo.mp4")))
                .andExpect(jsonPath("$.frames", notNullValue()))
                .andExpect(jsonPath("$.legacyEvidenceStatus", org.hamcrest.Matchers.is("operational_evidence")))
                .andExpect(jsonPath("$.reasoning", notNullValue()));
    }

    @Test
    @DisplayName("迁移后全部用例为 unreviewed，未被批量标成已复核")
    void allUnreviewedAfterMigration() throws Exception {
        mockMvc.perform(get("/api/cases").param("reviewStatus", "unreviewed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)));
        mockMvc.perform(get("/api/cases").param("reviewStatus", "reviewed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("按回答状态过滤：未核验的行不会出现在 not_found 结果里")
    void filterByAnswerStatus() throws Exception {
        // V8 之前 cf-failed-records-export 是 unreviewed + not_found，会被这个过滤命中；
        // 现在它落 NULL，过滤 not_found 必须查不到任何行，否则「没核验」又被算成「没找到回答」
        mockMvc.perform(get("/api/cases").param("answerStatus", "not_found"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/api/cases").param("answerStatus", "answered"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));
    }

    @Test
    @DisplayName("EVIDENCE_PARTIAL 迁移为 unknown + not_checked，未被推断")
    void partialEvidenceNotInferred() throws Exception {
        mockMvc.perform(get("/api/cases/cf-dedup-enabled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidenceType", org.hamcrest.Matchers.is("unknown")))
                .andExpect(jsonPath("$.evidenceSufficiency", org.hamcrest.Matchers.is("not_checked")));
        mockMvc.perform(get("/api/cases/cf-price-3999"))
                .andExpect(jsonPath("$.evidenceType", org.hamcrest.Matchers.is("unknown")))
                .andExpect(jsonPath("$.evidenceSufficiency", org.hamcrest.Matchers.is("not_checked")));
    }

    @Test
    @DisplayName("不存在的用例返回 404 统一错误结构")
    void unknownCase() throws Exception {
        mockMvc.perform(get("/api/cases/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", org.hamcrest.Matchers.is("CASE_NOT_FOUND")))
                .andExpect(jsonPath("$.error.message", notNullValue()));
    }

    @Test
    @DisplayName("非法过滤取值返回 400，不静默忽略")
    void invalidFilterValue() throws Exception {
        mockMvc.perform(get("/api/cases").param("evidenceType", "screenshot"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", org.hamcrest.Matchers.is("INVALID_PARAMETER")));
    }

    @Test
    @DisplayName("未知路径返回 JSON 404，不是 text/plain")
    void unknownPathIsJson() throws Exception {
        mockMvc.perform(get("/api/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/json")))
                .andExpect(jsonPath("$.error.code", notNullValue()));
    }

    @Test
    @DisplayName("不支持的方法返回 405 JSON")
    void methodNotAllowed() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/cases/cf-capacity-5000"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code", org.hamcrest.Matchers.is("METHOD_NOT_ALLOWED")));
    }

    @Test
    @DisplayName("用例数量大于 0，确认不是硬编码空数组")
    void notEmpty() throws Exception {
        mockMvc.perform(get("/api/cases"))
                .andExpect(jsonPath("$.length()", greaterThan(0)));
    }

    @Test
    @DisplayName("尚未核验的事项 answerStatus 为 null，不被当成「未找到回答」")
    void unreviewedCaseHasNullAnswerStatus() throws Exception {
        // V8 之前这一行是 unreviewed + not_found，界面上直接显示「未找到回答」，
        // 和「核验过、确认视频里没有回答」长得一模一样。这条断言把两者钉死。
        //
        // 断言写法说明：Spring 的 jsonPath(...).value(nullValue()) 对显式 null 会报
        // "No value at JSON path"，必须用 doesNotExist —— 它把「值为 null」和
        // 「字段不存在」都视为没有值，正好符合这里的校验目的。
        mockMvc.perform(get("/api/cases/cf-failed-records-export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus", org.hamcrest.Matchers.is("unreviewed")))
                .andExpect(jsonPath("$.answerStatus").doesNotExist());
    }
}
