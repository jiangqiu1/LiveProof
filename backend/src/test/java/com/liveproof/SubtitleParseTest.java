package com.liveproof;

import com.liveproof.dto.SubtitleCue;
import com.liveproof.service.SubtitleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SRT 解析单元测试：容忍 BOM / CRLF / 缺序号，时间统一为秒。
 */
class SubtitleParseTest {

    @Test
    @DisplayName("标准 SRT：序号、时间、多行文本")
    void parsesStandardBlock() {
        String raw = """
                1
                00:00:00,481 --> 00:00:10,061
                第一行
                第二行

                2
                00:01:01,500 --> 00:01:03,000
                下一条
                """;
        List<SubtitleCue> cues = SubtitleService.parse(raw);
        assertThat(cues).hasSize(2);
        assertThat(cues.get(0).index()).isEqualTo(1);
        assertThat(cues.get(0).startSec()).isEqualTo(0.481);
        assertThat(cues.get(0).endSec()).isEqualTo(10.061);
        assertThat(cues.get(0).text()).isEqualTo("第一行\n第二行");
        assertThat(cues.get(1).startSec()).isEqualTo(61.5);
    }

    @Test
    @DisplayName("CRLF + BOM 不影响解析")
    void toleratesCrlfAndBom() {
        String raw = "\uFEFF1\r\n00:00:01,000 --> 00:00:02,000\r\n文本\r\n";
        List<SubtitleCue> cues = SubtitleService.parse(raw);
        assertThat(cues).hasSize(1);
        assertThat(cues.get(0).startSec()).isEqualTo(1.0);
        assertThat(cues.get(0).text()).isEqualTo("文本");
    }

    @Test
    @DisplayName("缺少序号时仍能解析并自动编号")
    void toleratesMissingIndex() {
        String raw = """
                00:00:05,000 --> 00:00:06,000
                无序号
                """;
        List<SubtitleCue> cues = SubtitleService.parse(raw);
        assertThat(cues).hasSize(1);
        assertThat(cues.get(0).index()).isEqualTo(1);
    }

    @Test
    @DisplayName("空内容返回空列表，不抛异常")
    void emptyContent() {
        assertThat(SubtitleService.parse("")).isEmpty();
    }

    @Test
    @DisplayName("非法时间行被跳过，不产生脏数据")
    void skipsInvalidTiming() {
        String raw = """
                1
                00:00:xx,000 --> 00:00:06,000
                坏的
                """;
        assertThat(SubtitleService.parse(raw)).isEmpty();
    }
}
