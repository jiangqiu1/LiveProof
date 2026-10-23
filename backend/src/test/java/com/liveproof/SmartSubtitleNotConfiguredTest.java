package com.liveproof;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 未配置凭据时的行为。
 *
 * <p>本类**不**注入假凭据（沿用 application-test.yml 的清空配置），因此也顺带证明：
 * 测试环境不会意外打到真实保利威账号。
 *
 * <p>红线：未配置时一律 503 `POLYV_NOT_CONFIGURED`，**不允许**返回示例字幕或空字幕冒充结果。
 */
class SmartSubtitleNotConfiguredTest extends ApiTestBase {

    private static final String VIDEO = "candidateflow-demo-nosub.mp4";
    private static final String TASK_ID = "522143";

    @BeforeEach
    void seedLocalTask() {
        jdbcTemplate.update("DELETE FROM polyv_subtitle_tasks");
        jdbcTemplate.update("""
                INSERT INTO polyv_subtitle_tasks (video, vid, task_id, language, status, video_duration_sec, created_at)
                VALUES (?, 'testvid_0001', ?, 'chinese', 'ready', 106.000, NOW())
                """, VIDEO, TASK_ID);
    }

    @Test
    @DisplayName("接入状态：configured=false 且说明如何配置")
    void statusReportsNotConfigured() throws Exception {
        mockMvc.perform(get("/api/smart-subtitles/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.note").value(org.hamcrest.Matchers.containsString("POLYV_LIVE_APP_ID")));
    }

    @Test
    @DisplayName("创建任务：503，不发起任何云端调用")
    void createReturns503() throws Exception {
        mockMvc.perform(post("/api/smart-subtitles/tasks")
                        .contentType("application/json").content("{\"video\":\"" + VIDEO + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("POLYV_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("登记既有任务：503")
    void adoptReturns503() throws Exception {
        mockMvc.perform(post("/api/smart-subtitles/tasks/adopt")
                        .contentType("application/json")
                        .content("{\"video\":\"" + VIDEO + "\",\"taskId\":\"" + TASK_ID + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("POLYV_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("查询状态：503（先报部署问题，不谎报任务状态）")
    void refreshReturns503() throws Exception {
        mockMvc.perform(get("/api/smart-subtitles/tasks/" + TASK_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("POLYV_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("取字幕：503，不返回空字幕冒充成功")
    void cuesReturns503() throws Exception {
        mockMvc.perform(get("/api/smart-subtitles/tasks/" + TASK_ID + "/cues"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("POLYV_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("本地字幕接口不受影响（无凭据也能用）")
    void localSubtitlesStillWork() throws Exception {
        mockMvc.perform(get("/api/subtitles").param("video", VIDEO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cueCount").value(12));
    }
}
