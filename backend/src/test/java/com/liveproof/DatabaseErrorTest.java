package com.liveproof;

import com.liveproof.controller.CaseController;
import com.liveproof.exception.GlobalExceptionHandler;
import com.liveproof.service.CaseImportService;
import com.liveproof.service.CaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 数据库异常处理测试（修正旧实现缺陷 D1：数据库异常曾导致整个进程终止）。
 *
 * <p>用 standalone MockMvc 装配 Controller + 全局异常处理器，
 * 不启动数据源，从而可以精确注入 DataAccessException。
 * 期望：500 JSON、请求立即结束不挂起、响应体不泄露连接串或堆栈。
 */
class DatabaseErrorTest {

    private CaseService caseService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        caseService = mock(CaseService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CaseController(caseService, mock(CaseImportService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("数据库不可用：500 + DATABASE_ERROR，且不泄露内部信息")
    void databaseFailureReturns500() throws Exception {
        given(caseService.detail(anyString()))
                .willThrow(new DataAccessResourceFailureException(
                        "Communications link failure to jdbc:mysql://127.0.0.1:3306/liveproof"));

        mockMvc.perform(get("/api/cases/cf-capacity-5000"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code", org.hamcrest.Matchers.is("DATABASE_ERROR")))
                .andExpect(jsonPath("$.error.message", containsString("数据库")))
                .andExpect(jsonPath("$.error.message",
                        org.hamcrest.Matchers.not(containsString("jdbc"))))
                .andExpect(jsonPath("$.error.message",
                        org.hamcrest.Matchers.not(containsString("Communications link failure"))));
    }

    @Test
    @DisplayName("未知异常：500 + INTERNAL_ERROR，不返回堆栈")
    void unexpectedFailureReturns500() throws Exception {
        given(caseService.detail(anyString())).willThrow(new IllegalStateException("boom"));

        mockMvc.perform(get("/api/cases/cf-capacity-5000"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code", org.hamcrest.Matchers.is("INTERNAL_ERROR")))
                .andExpect(jsonPath("$.error.message",
                        org.hamcrest.Matchers.not(containsString("boom"))));
    }
}
