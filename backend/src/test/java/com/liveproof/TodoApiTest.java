package com.liveproof;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 待办接口测试：独立实体、可创建、可编辑、可完成、时间戳正确。
 */
class TodoApiTest extends ApiTestBase {

    private static final String CASE_ID = "cf-capacity-5000";

    private JsonNode createTodo(String title, String detail) throws Exception {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("caseId", CASE_ID);
        payload.put("title", title);
        payload.put("detail", detail);
        String body = mockMvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    private JsonNode readTodo(long id) throws Exception {
        String body = mockMvc.perform(get("/api/todos/" + id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    @Test
    @DisplayName("创建待办：状态 open，来源 manual，创建时间已写入")
    void create() throws Exception {
        JsonNode todo = createTodo("补一次 5000 条导入演示", "演示实际导入到上限");
        assertThat(todo.get("id").asLong()).isPositive();
        assertThat(todo.get("caseId").asText()).isEqualTo(CASE_ID);
        assertThat(todo.get("status").asText()).isEqualTo("open");
        assertThat(todo.get("source").asText()).isEqualTo("manual");
        assertThat(todo.get("createdAt").isNull()).isFalse();
        assertThat(todo.get("updatedAt").isNull()).isFalse();
        assertThat(todo.get("completedAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("创建后在列表与详情中均可读回（真实持久化）")
    void createdTodoIsListedAndReadable() throws Exception {
        long id = createTodo("补一个答复", "会后回复失败记录导出问题").get("id").asLong();

        mockMvc.perform(get("/api/todos").param("caseId", CASE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").exists());

        assertThat(readTodo(id).get("title").asText()).isEqualTo("补一个答复");
    }

    @Test
    @DisplayName("编辑待办内容")
    void updateContent() throws Exception {
        long id = createTodo("原始标题", "原始描述").get("id").asLong();

        JsonNode updated = objectMapper.readTree(mockMvc.perform(patch("/api/todos/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新标题\",\"detail\":\"新描述\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(updated.get("title").asText()).isEqualTo("新标题");
        assertThat(updated.get("detail").asText()).isEqualTo("新描述");
        assertThat(updated.get("status").asText()).isEqualTo("open");
        assertThat(readTodo(id).get("title").asText()).isEqualTo("新标题");
    }

    @Test
    @DisplayName("标记完成写入 completed_at，改回 open 时清空")
    void completeAndReopen() throws Exception {
        long id = createTodo("待完成", null).get("id").asLong();

        JsonNode done = objectMapper.readTree(mockMvc.perform(post("/api/todos/" + id + "/complete"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(done.get("status").asText()).isEqualTo("done");
        assertThat(done.get("completedAt").isNull()).isFalse();

        JsonNode reopened = objectMapper.readTree(mockMvc.perform(patch("/api/todos/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"open\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(reopened.get("status").asText()).isEqualTo("open");
        assertThat(reopened.get("completedAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("重复标记完成幂等，完成时间不被重写")
    void completeIsIdempotent() throws Exception {
        long id = createTodo("幂等", null).get("id").asLong();
        String first = objectMapper.readTree(mockMvc.perform(post("/api/todos/" + id + "/complete"))
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("completedAt").asText();

        mockMvc.perform(post("/api/todos/" + id + "/complete")).andExpect(status().isOk());
        assertThat(readTodo(id).get("completedAt").asText()).isEqualTo(first);
    }

    @Test
    @DisplayName("按状态过滤待办")
    void filterByStatus() throws Exception {
        long id = createTodo("过滤测试", null).get("id").asLong();
        mockMvc.perform(post("/api/todos/" + id + "/complete")).andExpect(status().isOk());

        mockMvc.perform(get("/api/todos").param("status", "done"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").exists());
        mockMvc.perform(get("/api/todos").param("status", "open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").doesNotExist());
    }

    @Test
    @DisplayName("关联不存在的用例返回 404 CASE_NOT_FOUND")
    void createWithUnknownCase() throws Exception {
        mockMvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseId\":\"no-such-case\",\"title\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", org.hamcrest.Matchers.is("CASE_NOT_FOUND")));
    }

    @Test
    @DisplayName("标题为空返回 400 校验错误")
    void createWithBlankTitle() throws Exception {
        mockMvc.perform(post("/api/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseId\":\"" + CASE_ID + "\",\"title\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", org.hamcrest.Matchers.is("VALIDATION_ERROR")));
    }

    @Test
    @DisplayName("非法状态值返回 400")
    void invalidStatus() throws Exception {
        long id = createTodo("非法状态", null).get("id").asLong();
        mockMvc.perform(patch("/api/todos/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"finished\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("时间戳语义：createdAt <= updatedAt，完成时 completedAt 不早于 updatedAt")
    void timestampConsistency() throws Exception {
        JsonNode created = createTodo("时间戳", null);
        java.time.LocalDateTime createdAt = java.time.LocalDateTime.parse(created.get("createdAt").asText());
        java.time.LocalDateTime updatedAt = java.time.LocalDateTime.parse(created.get("updatedAt").asText());
        assertThat(updatedAt).isAfterOrEqualTo(createdAt);

        long id = created.get("id").asLong();
        JsonNode done = objectMapper.readTree(mockMvc.perform(post("/api/todos/" + id + "/complete"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        java.time.LocalDateTime completedAt = java.time.LocalDateTime.parse(done.get("completedAt").asText());
        java.time.LocalDateTime updatedAfter = java.time.LocalDateTime.parse(done.get("updatedAt").asText());
        assertThat(completedAt).isAfterOrEqualTo(createdAt);
        assertThat(updatedAfter).isAfterOrEqualTo(completedAt);
    }

    @Test
    @DisplayName("不存在的待办返回 404")
    void unknownTodo() throws Exception {
        mockMvc.perform(get("/api/todos/99999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", org.hamcrest.Matchers.is("TODO_NOT_FOUND")));
    }

    @Test
    @DisplayName("空修改请求返回 400")
    void emptyPatch() throws Exception {
        long id = createTodo("空修改", null).get("id").asLong();
        mockMvc.perform(patch("/api/todos/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", org.hamcrest.Matchers.is("NO_UPDATABLE_FIELD")));
    }
}
