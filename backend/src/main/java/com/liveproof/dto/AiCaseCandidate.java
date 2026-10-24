package com.liveproof.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一条由 AI 从字幕里提取出来的<b>候选</b>核验事项。
 *
 * <p>三条语义约束（与「AI 只出建议」的全局口径一致）：
 * <ul>
 *   <li>它只是「待人工确认的问题清单」，<b>没有任何核验结论</b>——
 *       不携带 reviewStatus / answerStatus / evidenceType / evidenceSufficiency</li>
 *   <li>{@code claim} 是视频「声称了什么」（客观转述），不是「事实是什么」</li>
 *   <li>{@code quote} 必须是对应区间的字幕原文，用于人工回看核对；
 *       找不到原文时为空，不允许模型自造</li>
 * </ul>
 *
 * @param id               本批次内的稳定标识（{@code cand-1} 起），仅用于前端 key 与编辑定位
 * @param question         待核验的问题
 * @param claim            视频里的原话要点（转述）
 * @param startSec         建议时间区间起点（来自字幕，已对齐真实 cue）
 * @param endSec           建议时间区间终点
 * @param quote            该区间的字幕原文，可能为空
 * @param cueIndexes       覆盖到的字幕序号（升序），用于人工回看
 * @param duplicate        是否疑似重复（库内已有同问题，或本批前面已出现同问题）
 * @param duplicateOf      与库内已有事项重复时的 case id，否则 null
 * @param duplicateOfIndex 与本批第几条重复（1 起），否则 null
 */
public record AiCaseCandidate(
        String id,
        String question,
        String claim,
        BigDecimal startSec,
        BigDecimal endSec,
        String quote,
        List<Integer> cueIndexes,
        boolean duplicate,
        String duplicateOf,
        Integer duplicateOfIndex
) {
}
