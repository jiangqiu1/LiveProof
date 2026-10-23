package com.liveproof;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 复盘报告：统计必须与数据库一致，未检查项不得被写成已核验，待办状态如实反映。
 */
class ReportApiTest extends ApiTestBase {

    private JsonNode generateJson() throws Exception {
        String body = mockMvc.perform(post("/api/reports/review").param("ds", "cf"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    @Test
    @DisplayName("统计数字与数据库真实数据一致")
    void statsMatchDatabase() throws Exception {
        jdbcTemplate.update("UPDATE cases SET review_status='reviewed', answer_status='answered', "
                + "evidence_type='operational_evidence', evidence_sufficiency='sufficient' "
                + "WHERE id='cf-import-3-records'");
        jdbcTemplate.update("UPDATE cases SET review_status='in_review' WHERE id='cf-price-3999'");

        Integer reviewed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cases WHERE ds='cf' AND review_status='reviewed'", Integer.class);
        Integer unreviewed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cases WHERE ds='cf' AND review_status='unreviewed'", Integer.class);
        Integer sufficient = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cases WHERE ds='cf' AND evidence_sufficiency='sufficient'", Integer.class);

        JsonNode stats = generateJson().path("stats");
        assertThat(stats.path("total").asInt()).isEqualTo(6);
        assertThat(stats.path("reviewed").asInt()).isEqualTo(reviewed);
        assertThat(stats.path("unreviewed").asInt()).isEqualTo(unreviewed);
        assertThat(stats.path("inReview").asInt()).isEqualTo(1);
        assertThat(stats.path("sufficient").asInt()).isEqualTo(sufficient);
        assertThat(generateJson().path("aiUsed").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("未检查事项保留在「尚未检查」，不会被写成已核验")
    void unreviewedStaysUnreviewed() throws Exception {
        JsonNode report = generateJson();
        String markdown = report.path("markdown").asText();

        assertThat(markdown).contains("### 6.1 尚未人工检查（6 项）");
        assertThat(markdown).contains("cf-capacity-5000");
        assertThat(markdown).contains("核验进度 **尚未检查**");
        assertThat(markdown).doesNotContain("核验进度 **已核验**");
        assertThat(report.path("stats").path("unreviewed").asInt()).isEqualTo(6);
        assertThat(report.path("stats").path("reviewed").asInt()).isZero();
    }

    @Test
    @DisplayName("待办状态如实反映数据库，逐项复盘可追溯到待办")
    void todosAreReported() throws Exception {
        jdbcTemplate.update("INSERT INTO todos (case_id, title, detail, status, source) "
                + "VALUES ('cf-failed-records-export', '补一次失败记录导出演示', NULL, 'open', 'manual')");
        jdbcTemplate.update("INSERT INTO todos (case_id, title, detail, status, source, completed_at) "
                + "VALUES ('cf-price-3999', '确认价格页标注', NULL, 'done', 'manual', NOW())");

        JsonNode report = generateJson();
        JsonNode stats = report.path("stats");
        assertThat(stats.path("todoTotal").asInt()).isEqualTo(2);
        assertThat(stats.path("todoOpen").asInt()).isEqualTo(1);
        assertThat(stats.path("todoDone").asInt()).isEqualTo(1);

        String markdown = report.path("markdown").asText();
        assertThat(markdown).contains("补一次失败记录导出演示");
        assertThat(markdown).contains("待办统计：待处理 1 · 进行中 0 · 已完成 1");
        assertThat(markdown).contains("关联待办：补一次失败记录导出演示（待处理）");
    }

    @Test
    @DisplayName("已标记时间点会出现在证据索引，并带上对应字幕")
    void evidenceIndexUsesTimePoints() throws Exception {
        jdbcTemplate.update("UPDATE cases SET time_points='[22.0, 48.0]' WHERE id='cf-import-3-records'");

        String markdown = generateJson().path("markdown").asText();
        assertThat(markdown).contains("## 4. 证据索引");
        assertThat(markdown).contains("`00:22`");
        assertThat(markdown).contains("`00:48`");
        assertThat(markdown).contains("现在导入一份 CSV 文件");
    }

    @Test
    @DisplayName("Markdown 下载返回 attachment 与真实正文")
    void downloadReturnsMarkdown() throws Exception {
        mockMvc.perform(get("/api/reports/review.md").param("ds", "cf"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(content().string(containsString("LiveProof 复盘报告")))
                .andExpect(content().string(containsString("## 4. 证据索引")));
    }

    @Test
    @DisplayName("非法 ds 参数被拒绝")
    void invalidDatasetRejected() throws Exception {
        mockMvc.perform(post("/api/reports/review").param("ds", "../../etc/passwd"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("不存在的数据集返回 400 而不是空报告")
    void unknownDatasetRejected() throws Exception {
        mockMvc.perform(post("/api/reports/review").param("ds", "zz"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
    }
}
