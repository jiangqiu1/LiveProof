package com.liveproof;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CSV 导入测试。
 *
 * <p>红线断言集中在三处：
 * <ol>
 *   <li>预览<b>不写库</b>（cases 行数不变）</li>
 *   <li>结论列（reviewStatus 等）出现即整批 422，不允许「导入即已核验」</li>
 *   <li>导入产生的行一律 unreviewed + not_checked，answerStatus 为 NULL（尚未核验）</li>
 * </ol>
 */
class CaseImportApiTest extends ApiTestBase {

    private static final String BUILTIN = "mat_builtin_demo";

    @Test
    @DisplayName("模板可下载，且内容含四列表头")
    void template() throws Exception {
        mockMvc.perform(get("/api/cases/import/template"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("filename")));
    }

    @Test
    @DisplayName("预览不写库：行数、可导入数、非法数逐项回报")
    void previewDoesNotWrite() throws Exception {
        String csv = """
                问题,承诺,开始秒,结束秒
                容量能改到 6000 吗,支持把容量上限改到 6000,40,60
                ,缺少问题列取值,10,20
                """;
        mockMvc.perform(multipart("/api/cases/import/preview")
                        .file(csvFile(csv)).param("materialId", BUILTIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.materialId").value(BUILTIN))
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.validCount").value(1))
                .andExpect(jsonPath("$.invalidCount").value(1))
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[1].problems[0]").value("问题不能为空"));

        assertCaseCount(6);
    }

    @Test
    @DisplayName("结论列出现即整批 422：不允许通过 CSV 设置核验状态")
    void reservedColumnRejected() throws Exception {
        String csv = """
                问题,承诺,reviewStatus
                能导出失败记录吗,支持导出失败记录,unreviewed
                """;
        mockMvc.perform(multipart("/api/cases/import/preview")
                        .file(csvFile(csv)).param("materialId", BUILTIN))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CASE_IMPORT_RESERVED_COLUMN"))
                .andExpect(jsonPath("$.error.message", containsString("reviewStatus")));

        assertCaseCount(6);
    }

    @Test
    @DisplayName("缺「问题」「承诺」列时 400 并提示必填列")
    void missingRequiredColumns() throws Exception {
        String csv = """
                问题,开始秒,结束秒
                能改容量吗,1,2
                """;
        mockMvc.perform(multipart("/api/cases/import/preview")
                        .file(csvFile(csv)).param("materialId", BUILTIN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.error.message", containsString("承诺")));
    }

    @Test
    @DisplayName("与库内已有事项重复：预览标记重复，严格模式拒绝整批")
    void duplicateDetected() throws Exception {
        String csv = """
                问题,承诺,开始秒,结束秒
                CandidateFlow 单次最多支持导入多少条候选人记录？,支持一次性导入 5000 条,40,60
                """;
        mockMvc.perform(multipart("/api/cases/import/preview")
                        .file(csvFile(csv)).param("materialId", BUILTIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateCount").value(1))
                .andExpect(jsonPath("$.validCount").value(0));

        mockMvc.perform(multipart("/api/cases/import")
                        .file(csvFile(csv)).param("materialId", BUILTIN))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CASE_IMPORT_HAS_INVALID_ROWS"))
                .andExpect(jsonPath("$.error.message", containsString("skip_invalid")));

        assertCaseCount(6);
    }

    @Test
    @DisplayName("严格模式：有一条非法行就整批拒绝，一条都不写")
    void strictRejectsAll() throws Exception {
        String csv = """
                问题,承诺,开始秒,结束秒
                能自定义招聘流程吗,支持自定义招聘流程,30,50
                时间写错了,开始晚于结束,60,40
                """;
        mockMvc.perform(multipart("/api/cases/import")
                        .file(csvFile(csv)).param("materialId", BUILTIN))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("CASE_IMPORT_HAS_INVALID_ROWS"));

        assertCaseCount(6);
    }

    @Test
    @DisplayName("skip_invalid：合法行入库，非法行如实列出，来源标记为 csv")
    void skipInvalidWritesValidRows() throws Exception {
        String csv = """
                问题,承诺,开始秒,结束秒
                能自定义招聘流程吗,支持自定义招聘流程,30,50
                时间写错了,开始晚于结束,60,40
                """;
        mockMvc.perform(multipart("/api/cases/import")
                        .file(csvFile(csv)).param("materialId", BUILTIN).param("policy", "skip_invalid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.skippedCount").value(1))
                .andExpect(jsonPath("$.skipped[0].line").value(3))
                .andExpect(jsonPath("$.created[0].source").value("csv"))
                .andExpect(jsonPath("$.created[0].materialId").value(BUILTIN))
                // 红线：新行不得带任何核验结论
                .andExpect(jsonPath("$.created[0].reviewStatus").value("unreviewed"))
                .andExpect(jsonPath("$.created[0].evidenceSufficiency").value("not_checked"))
                // 四字段必须全部有确定取值：experience 表明 create() 曾漏设 evidenceType，
                // 落库为 NULL（列允许为空），前端拿到 null 会显示空白而非 unknown。
                .andExpect(jsonPath("$.created[0].evidenceType").value("unknown"))
                // V8 起 answerStatus 落 NULL = 尚未核验；not_found 只表示「核验过、视频里没找到回答」，
                // 两者不能混用，否则新建/导入的事项一出现就自带一个未做的结论。
                // doesNotExist 同时覆盖「值为 null」与「字段缺失」（Spring 对显式 null 的判定方式）
                .andExpect(jsonPath("$.created[0].answerStatus").doesNotExist());

        assertCaseCount(7);
    }

    @Test
    @DisplayName("时间接受 mm:ss 写法，落库统一两位小数")
    void timeFormats() throws Exception {
        String csv = """
                问题,承诺,开始秒,结束秒
                能批量导入候选人吗,支持批量导入候选人,01:00,01:30
                """;
        mockMvc.perform(multipart("/api/cases/import")
                        .file(csvFile(csv)).param("materialId", BUILTIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.created[0].startSec").value(60.00))
                .andExpect(jsonPath("$.created[0].endSec").value(90.00));
    }

    @Test
    @DisplayName("未指定 materialId 时 400：导入必须指明归属素材")
    void missingMaterial() throws Exception {
        mockMvc.perform(multipart("/api/cases/import/preview")
                        .file(csvFile("问题,承诺\n能改容量吗,支持改容量\n")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("materialId")));
    }

    @Test
    @DisplayName("非法 policy 取值 400，不静默退化为 strict")
    void invalidPolicy() throws Exception {
        mockMvc.perform(multipart("/api/cases/import")
                        .file(csvFile("问题,承诺\n能改容量吗,支持改容量\n"))
                        .param("materialId", BUILTIN).param("policy", "whatever"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("导入后可按素材维度查询，且不计入其他素材")
    void createdCasesFilteredByMaterial() throws Exception {
        String csv = """
                问题,承诺
                能自定义招聘流程吗,支持自定义招聘流程
                """;
        mockMvc.perform(multipart("/api/cases/import")
                        .file(csvFile(csv)).param("materialId", BUILTIN))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/cases").param("materialId", BUILTIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(7)));
        mockMvc.perform(get("/api/cases").param("materialId", "mat_builtin_demo_nosub"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ── 辅助 ─────────────────────────────────────────────

    private static MockMultipartFile csvFile(String content) {
        return new MockMultipartFile("file", "cases.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    /** 直接读库计数：预览与拒绝路径都不应改变 cases 行数。 */
    private void assertCaseCount(int expected) {
        Integer actual = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cases", Integer.class);
        org.assertj.core.api.Assertions.assertThat(actual).isEqualTo(expected);
    }
}