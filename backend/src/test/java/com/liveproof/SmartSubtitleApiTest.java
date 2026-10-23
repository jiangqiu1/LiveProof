package com.liveproof;

import com.liveproof.exception.PolyvErrors;
import com.liveproof.polyv.PolyvSubtitleClient;
import com.liveproof.polyv.PolyvSubtitleSnapshot;
import com.liveproof.polyv.PolyvTaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 智能字幕接口与持久化测试。
 *
 * <p>云端客户端被替换为桩（唯一允许 mock 的位置），这样断言的是<b>我们自己的</b>
 * 防重复创建、登记校验、状态流转、字幕归一化与落库行为，而不是保利威的可用性。
 * 真实云端链路由 {@code scripts/verify-smart-subtitle-backend.mjs} 单独验证。
 */
@TestPropertySource(properties = {
        "liveproof.polyv.app-id=test-app-id",
        "liveproof.polyv.app-secret=test-app-secret-not-real"
})
class SmartSubtitleApiTest extends ApiTestBase {

    private static final String VIDEO = "candidateflow-demo-nosub.mp4";
    private static final String VID = "testvid_0001";
    private static final String TASK_ID = "522143";

    @MockitoBean
    private PolyvSubtitleClient polyvClient;

    @BeforeEach
    void cleanTasks() {
        jdbcTemplate.update("DELETE FROM polyv_subtitle_tasks");
    }

    // ---------------------------------------------------------------- 工具

    /** 造一份与真实结构一致的快照：每 2 秒一条，间隔 0.2 秒。 */
    private static PolyvSubtitleSnapshot readySnapshot(int count) {
        List<PolyvSubtitleSnapshot.PolyvCue> cues = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long start = i * 2000L;
            cues.add(new PolyvSubtitleSnapshot.PolyvCue(start, start + 1800, "第" + (i + 1) + "条字幕"));
        }
        return new PolyvSubtitleSnapshot(522143L, PolyvTaskState.READY, "智能字幕", null, cues);
    }

    private String createBody() {
        return "{\"video\":\"" + VIDEO + "\"}";
    }

    private String adoptBody(String taskId) {
        return "{\"video\":\"" + VIDEO + "\",\"taskId\":\"" + taskId + "\",\"createdAt\":\"2026-10-23T15:24:43\"}";
    }

    private String adoptBodyWithoutCreatedAt(String taskId) {
        return "{\"video\":\"" + VIDEO + "\",\"taskId\":\"" + taskId + "\"}";
    }

    private int taskRows() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM polyv_subtitle_tasks WHERE video = ?", Integer.class, VIDEO);
        return count == null ? 0 : count;
    }

    private Map<String, Object> taskRow() {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM polyv_subtitle_tasks WHERE video = ? ORDER BY id DESC LIMIT 1", VIDEO);
    }

    private void insertTask(String video, String vid, String taskId) {
        jdbcTemplate.update("""
                INSERT INTO polyv_subtitle_tasks (video, vid, task_id, language, status, video_duration_sec, created_at)
                VALUES (?, ?, ?, 'chinese', 'ready', 106.000, NOW())
                """, video, vid, taskId);
    }

    // ---------------------------------------------------------------- 状态

    @Test
    @DisplayName("接入状态：已配置、VID 已映射、时长基准可见，且不回显凭据")
    void statusReportsConfiguration() throws Exception {
        mockMvc.perform(get("/api/smart-subtitles/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.baseUrlHost").value("api.polyv.net"))
                .andExpect(jsonPath("$.defaultLanguage").value("chinese"))
                .andExpect(jsonPath("$.videos[?(@.video=='" + VIDEO + "')].vid",
                        org.hamcrest.Matchers.contains(VID)))
                .andExpect(jsonPath("$.videos[?(@.video=='" + VIDEO + "')].vidConfigured",
                        org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.videos[?(@.video=='" + VIDEO + "')].durationSec",
                        org.hamcrest.Matchers.contains(106.0)))
                .andExpect(jsonPath("$.note").value(org.hamcrest.Matchers.containsString("已配置")));
    }

    // ---------------------------------------------------------------- 创建

    @Test
    @DisplayName("创建任务：201 落库，状态 created，未消耗第二次资源点")
    void createPersistsTask() throws Exception {
        when(polyvClient.createTask(VID, "chinese")).thenReturn(522143L);

        mockMvc.perform(post("/api/smart-subtitles/tasks")
                        .contentType("application/json").content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.status").value("created"))
                .andExpect(jsonPath("$.reused").value(false));

        assertThat(taskRows()).isEqualTo(1);
        Map<String, Object> row = taskRow();
        assertThat(row.get("vid")).isEqualTo(VID);
        assertThat(row.get("task_id")).isEqualTo(TASK_ID);
        assertThat(row.get("subtitle_count")).isNull();
        assertThat(row.get("last_checked_at")).isNull();
    }

    @Test
    @DisplayName("创建任务：同视频第二次请求复用既有任务，不再调用创建接口")
    void createReusesExistingTask() throws Exception {
        when(polyvClient.createTask(VID, "chinese")).thenReturn(522143L);

        mockMvc.perform(post("/api/smart-subtitles/tasks")
                        .contentType("application/json").content(createBody()))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/smart-subtitles/tasks")
                        .contentType("application/json").content(createBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.reused").value(true));

        verify(polyvClient, times(1)).createTask(anyString(), anyString());
        assertThat(taskRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("创建任务：两个本地素材共用同一 VID 时复用同一任务，不重复消耗资源点")
    void createReusesTaskSharedBySameVid() throws Exception {
        insertTask(VIDEO, VID, TASK_ID);

        // candidateflow-demo.mp4 与 -nosub 是同一段演示（时间轴一致），映射到同一个 VID
        mockMvc.perform(post("/api/smart-subtitles/tasks")
                        .contentType("application/json")
                        .content("{\"video\":\"candidateflow-demo.mp4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.reused").value(true));

        verify(polyvClient, times(0)).createTask(anyString(), anyString());
        // 不新增登记行：一个云端视频只留一份任务登记
        assertThat(taskRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("创建任务：数据库唯一键阻止同一 (video, vid) 登记两条")
    void uniqueKeyBlocksDuplicates() {
        insertTask(VIDEO, VID, "111");
        assertThatThrownBy(() -> insertTask(VIDEO, VID, "222"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("创建任务：非白名单视频 400，不发起云端调用")
    void createRejectsUnknownVideo() throws Exception {
        mockMvc.perform(post("/api/smart-subtitles/tasks")
                        .contentType("application/json").content("{\"video\":\"demo-video.mp4\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));

        verify(polyvClient, times(0)).createTask(anyString(), anyString());
        assertThat(taskRows()).isZero();
    }

    @Test
    @DisplayName("创建任务：资源点不足 503，不留下任何登记")
    void createPropagatesQuotaError() throws Exception {
        when(polyvClient.createTask(VID, "chinese")).thenThrow(PolyvErrors.quotaExhausted("资源点不足"));

        mockMvc.perform(post("/api/smart-subtitles/tasks")
                        .contentType("application/json").content(createBody()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("POLYV_QUOTA_EXHAUSTED"));

        assertThat(taskRows()).isZero();
    }

    // ---------------------------------------------------------------- 登记既有任务

    @Test
    @DisplayName("登记既有任务：只读校验通过后落库，记录真实条数与成功时间")
    void adoptRegistersExistingTask() throws Exception {
        when(polyvClient.fetchTask(TASK_ID)).thenReturn(readySnapshot(29));

        mockMvc.perform(post("/api/smart-subtitles/tasks/adopt")
                        .contentType("application/json").content(adoptBody(TASK_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.subtitleCount").value(29))
                .andExpect(jsonPath("$.language").value("chinese"));

        Map<String, Object> row = taskRow();
        assertThat(row.get("task_id")).isEqualTo(TASK_ID);
        assertThat(row.get("status")).isEqualTo("ready");
        assertThat(row.get("subtitle_count")).isEqualTo(29);
        assertThat(row.get("last_checked_at")).isNotNull();
        assertThat(row.get("last_success_at")).isNotNull();
        assertThat(row.get("created_at").toString()).startsWith("2026-10-23");
        verify(polyvClient, times(1)).fetchTask(TASK_ID);
        verify(polyvClient, times(0)).createTask(anyString(), anyString());
    }

    @Test
    @DisplayName("登记既有任务：重复登记幂等，不产生第二条记录")
    void adoptIsIdempotent() throws Exception {
        when(polyvClient.fetchTask(TASK_ID)).thenReturn(readySnapshot(29));

        mockMvc.perform(post("/api/smart-subtitles/tasks/adopt")
                        .contentType("application/json").content(adoptBody(TASK_ID)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/smart-subtitles/tasks/adopt")
                        .contentType("application/json").content(adoptBody(TASK_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtitleCount").value(29));

        assertThat(taskRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("登记既有任务：重复登记未带 createdAt 时保留首次登记时间，不被冲成当前时间")
    void adoptKeepsOriginalCreatedAtWhenReplayedWithoutIt() throws Exception {
        when(polyvClient.fetchTask(TASK_ID)).thenReturn(readySnapshot(29));

        mockMvc.perform(post("/api/smart-subtitles/tasks/adopt")
                        .contentType("application/json").content(adoptBody(TASK_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdAt").value("2026-10-23T15:24:43"));

        // 无参重放：只带 video + taskId
        mockMvc.perform(post("/api/smart-subtitles/tasks/adopt")
                        .contentType("application/json").content(adoptBodyWithoutCreatedAt(TASK_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdAt").value("2026-10-23T15:24:43"))
                .andExpect(jsonPath("$.subtitleCount").value(29));

        assertThat(taskRows()).isEqualTo(1);
        assertThat(String.valueOf(taskRow().get("created_at")).replace('T', ' '))
                .startsWith("2026-10-23 15:24:43");
    }

    @Test
    @DisplayName("登记既有任务：云端不存在 404，且不写库")
    void adoptRejectsUnknownTask() throws Exception {
        when(polyvClient.fetchTask("999999999")).thenThrow(PolyvErrors.taskNotFoundInPlatform());

        mockMvc.perform(post("/api/smart-subtitles/tasks/adopt")
                        .contentType("application/json").content(adoptBody("999999999")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POLYV_TASK_NOT_FOUND"));

        assertThat(taskRows()).isZero();
    }

    @Test
    @DisplayName("登记既有任务：同一 taskId 已绑定别的视频 -> 409")
    void adoptRejectsTaskBoundToAnotherVideo() throws Exception {
        insertTask("candidateflow-demo.mp4", "othervid_0002", TASK_ID);

        mockMvc.perform(post("/api/smart-subtitles/tasks/adopt")
                        .contentType("application/json").content(adoptBody(TASK_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SUBTITLE_TASK_ALREADY_BOUND"));

        verify(polyvClient, times(0)).fetchTask(anyString());
    }

    @Test
    @DisplayName("登记既有任务：taskId 非数字 400")
    void adoptRejectsMalformedTaskId() throws Exception {
        mockMvc.perform(post("/api/smart-subtitles/tasks/adopt")
                        .contentType("application/json").content(adoptBody("abc")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // ---------------------------------------------------------------- 状态查询

    @Test
    @DisplayName("查询状态：生成中写 processing，不返回空字幕")
    void refreshKeepsProcessing() throws Exception {
        insertTask(VIDEO, VID, TASK_ID);
        when(polyvClient.fetchTask(TASK_ID)).thenReturn(
                new PolyvSubtitleSnapshot(522143L, PolyvTaskState.PROCESSING, null, null, List.of()));

        mockMvc.perform(get("/api/smart-subtitles/tasks/" + TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processing"))
                .andExpect(jsonPath("$.subtitleCount").value(org.hamcrest.Matchers.nullValue()));

        assertThat(taskRow().get("status")).isEqualTo("processing");
        assertThat(taskRow().get("last_checked_at")).isNotNull();
        assertThat(taskRow().get("last_success_at")).isNull();
    }

    @Test
    @DisplayName("查询状态：云端已删除 -> 404 并把 not_found 落库（错误不被事务吞掉）")
    void refreshPersistsNotFound() throws Exception {
        insertTask(VIDEO, VID, TASK_ID);
        when(polyvClient.fetchTask(TASK_ID)).thenThrow(PolyvErrors.taskNotFoundInPlatform());

        mockMvc.perform(get("/api/smart-subtitles/tasks/" + TASK_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POLYV_TASK_NOT_FOUND"));

        assertThat(taskRow().get("status")).isEqualTo("not_found");
        assertThat(taskRow().get("error_code")).isEqualTo("POLYV_TASK_NOT_FOUND");
    }

    @Test
    @DisplayName("查询状态：本地未登记 404 SUBTITLE_TASK_NOT_FOUND")
    void refreshRejectsUnknownLocalTask() throws Exception {
        mockMvc.perform(get("/api/smart-subtitles/tasks/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SUBTITLE_TASK_NOT_FOUND"));

        verify(polyvClient, times(0)).fetchTask(anyString());
    }

    // ---------------------------------------------------------------- 字幕

    @Test
    @DisplayName("取字幕：毫秒转秒后返回，并刷新成功时间与条数")
    void cuesReturnsNormalizedSeconds() throws Exception {
        insertTask(VIDEO, VID, TASK_ID);
        when(polyvClient.fetchTask(TASK_ID)).thenReturn(readySnapshot(29));

        mockMvc.perform(get("/api/smart-subtitles/tasks/" + TASK_ID + "/cues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCueCount").value(29))
                .andExpect(jsonPath("$.cueCount").value(29))
                .andExpect(jsonPath("$.cues[0].index").value(1))
                .andExpect(jsonPath("$.cues[0].startSec").value(0.0))
                .andExpect(jsonPath("$.cues[0].endSec").value(1.8))
                .andExpect(jsonPath("$.cues[1].startSec").value(2.0))
                .andExpect(jsonPath("$.status").value("ready"))
                // cue 结构与本地 SRT 完全一致，靠 source 区分来源
                .andExpect(jsonPath("$.source").value("cloud"))
                .andExpect(jsonPath("$.subtitleName").value("智能字幕"));

        assertThat(taskRow().get("subtitle_count")).isEqualTo(29);
        assertThat(taskRow().get("last_success_at")).isNotNull();
    }

    @Test
    @DisplayName("取字幕：时间窗按区间重叠筛选，不是完整包含")
    void cuesFilterByOverlap() throws Exception {
        insertTask(VIDEO, VID, TASK_ID);
        when(polyvClient.fetchTask(TASK_ID)).thenReturn(readySnapshot(29));

        // 1.7–2.1 落在第 1 条(0–1.8) 与第 2 条(2.0–3.8) 之间，两条都必须返回
        mockMvc.perform(get("/api/smart-subtitles/tasks/" + TASK_ID + "/cues")
                        .param("from", "1.7").param("to", "2.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCueCount").value(29))
                .andExpect(jsonPath("$.cueCount").value(2))
                .andExpect(jsonPath("$.cues[0].index").value(1))
                .andExpect(jsonPath("$.cues[1].index").value(2))
                .andExpect(jsonPath("$.windowFromSec").value(1.7));
    }

    @Test
    @DisplayName("取字幕：时间窗反向 400")
    void cuesRejectsInvertedWindow() throws Exception {
        insertTask(VIDEO, VID, TASK_ID);

        mockMvc.perform(get("/api/smart-subtitles/tasks/" + TASK_ID + "/cues")
                        .param("from", "10").param("to", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("取字幕：云端字幕顺序错乱 -> 422，并把 failed 与脱敏原因落库")
    void cuesRejectsInvalidSubtitles() throws Exception {
        insertTask(VIDEO, VID, TASK_ID);
        when(polyvClient.fetchTask(TASK_ID)).thenReturn(new PolyvSubtitleSnapshot(
                522143L, PolyvTaskState.READY, "智能字幕", null,
                List.of(new PolyvSubtitleSnapshot.PolyvCue(5000, 6000, "后"),
                        new PolyvSubtitleSnapshot.PolyvCue(3000, 4000, "前"))));

        mockMvc.perform(get("/api/smart-subtitles/tasks/" + TASK_ID + "/cues"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("POLYV_SUBTITLE_INVALID"));

        Map<String, Object> row = taskRow();
        assertThat(row.get("status")).isEqualTo("failed");
        assertThat(row.get("error_code")).isEqualTo("POLYV_SUBTITLE_INVALID");
        assertThat(row.get("subtitle_count")).isNull();
        assertThat(row.get("last_success_at")).isNull();
    }

    @Test
    @DisplayName("取字幕：仍生成中 -> 409，不返回空数组")
    void cuesRejectsProcessingTask() throws Exception {
        insertTask(VIDEO, VID, TASK_ID);
        when(polyvClient.fetchTask(TASK_ID)).thenReturn(
                new PolyvSubtitleSnapshot(522143L, PolyvTaskState.PROCESSING, null, null, List.of()));

        mockMvc.perform(get("/api/smart-subtitles/tasks/" + TASK_ID + "/cues"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POLYV_TASK_PROCESSING"));

        assertThat(taskRow().get("status")).isEqualTo("processing");
    }

    @Test
    @DisplayName("任务列表只读本地登记，可按视频过滤")
    void listReturnsLocalRecords() throws Exception {
        insertTask(VIDEO, VID, TASK_ID);

        mockMvc.perform(get("/api/smart-subtitles/tasks").param("video", VIDEO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].taskId").value(TASK_ID));

        verify(polyvClient, times(0)).fetchTask(anyString());
    }

    // ---------------------------------------------------------------- 兼容

    @Test
    @DisplayName("既有本地字幕接口行为不变（12 条人工 SRT）")
    void localSubtitleEndpointUnchanged() throws Exception {
        mockMvc.perform(get("/api/subtitles").param("video", VIDEO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.video").value(VIDEO))
                .andExpect(jsonPath("$.subtitleFile").value("candidateflow-demo.srt"))
                .andExpect(jsonPath("$.language").value("zh"))
                .andExpect(jsonPath("$.cueCount").value(12));

        verify(polyvClient, times(0)).fetchTask(anyString());
        verify(polyvClient, times(0)).createTask(anyString(), anyString());
    }
}
