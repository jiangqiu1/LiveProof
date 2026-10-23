package com.liveproof.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liveproof.ai.AiClient;
import com.liveproof.ai.AiCompletion;
import com.liveproof.entity.CaseEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 报告 AI 归纳的边界测试：未配置时降级、编造数字时过滤。
 */
class ReportNarrativeServiceTest {

    private final AiClient client = mock(AiClient.class);
    private final ReportNarrativeService service = new ReportNarrativeService(client, new ObjectMapper());

    @Test
    @DisplayName("未配置密钥时降级为无 AI 归纳，并给出说明")
    void degradesWhenNotConfigured() {
        when(client.isConfigured()).thenReturn(false);
        ReportNarrativeService.Narrative narrative =
                service.build("数据集", List.of(), List.of(), Map.of("复盘事项总数", 6));
        assertThat(narrative.used()).isFalse();
        assertThat(narrative.notes()).anyMatch(note -> note.contains("未配置"));
    }

    @Test
    @DisplayName("模型失败不影响报告：返回无归纳 + 原因")
    void handlesModelFailure() {
        when(client.isConfigured()).thenReturn(true);
        when(client.complete(anyString(), anyString(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("boom"));
        ReportNarrativeService.Narrative narrative =
                service.build("数据集", List.of(), List.of(), Map.of("复盘事项总数", 6));
        assertThat(narrative.used()).isFalse();
        assertThat(narrative.notes()).anyMatch(note -> note.contains("调用失败"));
    }

    @Test
    @DisplayName("模型编造的统计数字所在句子被整句过滤")
    void filtersFabricatedNumbers() {
        when(client.isConfigured()).thenReturn(true);
        CaseEntity entity = new CaseEntity();
        entity.setId("cf-a");
        entity.setQuestion("问题");
        entity.setStartSec(new java.math.BigDecimal("20.00"));
        entity.setEndSec(new java.math.BigDecimal("30.00"));
        when(client.complete(anyString(), anyString(), any(), anyBoolean())).thenReturn(new AiCompletion("""
                {"overview": "整体有 6 条待核验。其中 3 条完全缺失。",
                 "findings": ["共 42 条未核验，风险很高。", "证据形态以口头承诺为主。"],
                 "risks": ["失败分支未覆盖。"]}
                """, "deepseek-flash", 10, 10));

        Map<String, Integer> stats = new LinkedHashMap<>();
        stats.put("复盘事项总数", 6);
        stats.put("尚未检查", 0);
        ReportNarrativeService.Narrative narrative =
                service.build("数据集", List.of(entity), List.of(), stats);

        assertThat(narrative.used()).isTrue();
        // "6" 在统计里存在，"3" 不在 → 含 3 的句子被丢弃，只保留前半句
        assertThat(narrative.overview()).isEqualTo("整体有 6 条待核验。");
        // "42" 是编造的统计，整条被丢弃
        assertThat(narrative.findings()).containsExactly("证据形态以口头承诺为主。");
        assertThat(narrative.notes()).anyMatch(note -> note.contains("无法核对"));
    }

    @Test
    @DisplayName("数字守卫：允许出现摘要内数字，未知数字整句丢弃")
    void guardNumbersBehaviour() {
        assertThat(service.guardNumbers("共 6 条未核验。", Set.of("6"))).isEqualTo("共 6 条未核验。");
        assertThat(service.guardNumbers("共 7 条未核验。其余可用。", Set.of("6"))).isEqualTo("其余可用。");
        assertThat(service.guardNumbers("全部编造 99 条。", Set.of("6"))).isNull();
        assertThat(service.guardNumbers("没有任何数字的句子。", Set.of())).isEqualTo("没有任何数字的句子。");
    }

    @Test
    @DisplayName("千分位不拆成假数字：5,000 按 5000 整体核对")
    void handlesThousandSeparator() {
        // 「5,000」不能被拆成 5 与 000；放行 5000 后整句保留
        assertThat(service.guardNumbers("承诺单次支持 5,000 条。", Set.of("5000")))
                .isEqualTo("承诺单次支持 5,000 条。");
        // 未放行时整句丢弃，而不是误以为 5 是已知数字而保留
        assertThat(service.guardNumbers("承诺单次支持 5,000 条。", Set.of("5"))).isNull();
    }

    @Test
    @DisplayName("事项文本里本来就有的数字可作为可核对事实放行")
    void allowsNumbersFromCaseText() {
        when(client.isConfigured()).thenReturn(true);
        CaseEntity entity = new CaseEntity();
        entity.setId("cf-capacity");
        entity.setQuestion("单次最多支持多少条候选人记录？");
        entity.setClaim("单次最多支持 5,000 条候选人记录。");
        when(client.complete(anyString(), anyString(), any(), anyBoolean())).thenReturn(new AiCompletion("""
                {"overview": "承诺单次支持 5,000 条。",
                 "findings": ["该承诺属于 8,888 条的夸大表述。"],
                 "risks": []}
                """, "deepseek-flash", 10, 10));

        Map<String, Integer> stats = new LinkedHashMap<>();
        stats.put("复盘事项总数", 1);
        ReportNarrativeService.Narrative narrative =
                service.build("数据集", List.of(entity), List.of(), stats);

        // 5,000 来自 claim 原文 → 保留；8,888 任何地方都没有 → 丢弃
        assertThat(narrative.overview()).isEqualTo("承诺单次支持 5,000 条。");
        assertThat(narrative.findings()).isEmpty();
    }
}
