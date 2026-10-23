package com.liveproof;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 集成测试基类。
 *
 * <p>数据源为隔离测试库 liveproof_test（由生产库可还原备份 + 迁移脚本构建）。
 * 类级 @Transactional 保证每个测试方法结束时回滚，不留下写入痕迹。
 *
 * <p>{@code resetFixture()} 在事务内把 6 条 cf 用例恢复为迁移后的基线值并清空待办，
 * 使断言不依赖此前手工写入的状态（例如端到端联调时改判过的字段）。
 * 这些重置同样属于事务的一部分，测试结束即回滚。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
abstract class ApiTestBase {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /** 迁移基线：docs/08 第 2 节映射表的执行结果。 */
    private static final String[][] BASELINE = {
            {"cf-capacity-5000", "unreviewed", "answered", "unknown", "insufficient"},
            {"cf-dedup-enabled", "unreviewed", "answered", "unknown", "not_checked"},
            {"cf-failed-records-export", "unreviewed", "not_found", "unknown", "insufficient"},
            {"cf-fictional-data", "unreviewed", "answered", "operational_evidence", "sufficient"},
            {"cf-import-3-records", "unreviewed", "answered", "operational_evidence", "sufficient"},
            {"cf-price-3999", "unreviewed", "answered", "unknown", "not_checked"},
    };

    @BeforeEach
    void resetFixture() {
        for (String[] row : BASELINE) {
            jdbcTemplate.update("""
                    UPDATE cases
                       SET review_status = ?, answer_status = ?, evidence_type = ?, evidence_sufficiency = ?,
                           time_points = NULL
                     WHERE id = ?
                    """, row[1], row[2], row[3], row[4], row[0]);
        }
        jdbcTemplate.update("DELETE FROM todos");
    }
}
