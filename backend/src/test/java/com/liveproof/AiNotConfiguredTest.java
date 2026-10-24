package com.liveproof;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 未配置运行密钥时的行为：必须明确报"未配置"，不得返回任何示例/模拟分析。
 */
class AiNotConfiguredTest extends ApiTestBase {

    @Test
    @DisplayName("未配置密钥时核验接口返回 503 AI_NOT_CONFIGURED")
    void reviewRequiresConfiguration() throws Exception {
        mockMvc.perform(post("/api/cases/cf-import-3-records/ai-review"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("AI_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.error.message", containsString("DEEPSEEK_API_KEY")));
    }

    @Test
    @DisplayName("未配置密钥时候选事项生成返回 503，不返回任何示例候选")
    void candidateGenerationRequiresConfiguration() throws Exception {
        mockMvc.perform(post("/api/ai/case-candidates").param("materialId", "mat_builtin_demo"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("AI_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("状态接口如实报告未配置，并说明 FFmpeg 情况")
    void statusReportsNotConfigured() throws Exception {
        mockMvc.perform(get("/api/ai/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.note", containsString("未配置")));
    }

    @Test
    @DisplayName("未配置时报告仍可生成，但明确标注无 AI 归纳")
    void reportWorksWithoutAi() throws Exception {
        mockMvc.perform(post("/api/reports/review").param("ds", "cf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiUsed").value(false))
                .andExpect(jsonPath("$.notes[0]", containsString("未配置")))
                .andExpect(jsonPath("$.markdown", containsString("## 1. 复盘概览")));
    }
}
