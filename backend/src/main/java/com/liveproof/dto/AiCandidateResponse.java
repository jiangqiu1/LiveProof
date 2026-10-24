package com.liveproof.dto;

import java.util.List;

/**
 * AI 从字幕生成候选核验事项的结果。
 *
 * <p><b>这个接口不写库</b>：{@code candidates} 只是候选清单，
 * 用户在前端勾选、编辑、确认后才走 {@code POST /api/cases/from-candidates} 落库。
 *
 * @param materialId     素材 id
 * @param subtitleSource 实际使用的字幕来源：{@code local} 或 {@code cloud}
 * @param subtitleFile   本地字幕文件名；云端来源恒为 null（不张冠李戴）
 * @param totalCues      该字幕的总条数
 * @param cuesUsed       实际提交给模型的条数（可能因字符预算被抽样）
 * @param truncated      是否因字符上限做过抽样裁剪
 * @param model          模型名（仅用于展示）
 * @param elapsedMs      本次耗时（毫秒）
 * @param candidates     候选清单（按 startSec 升序）
 * @param duplicateCount 其中疑似重复的条数（前端据此决定默认是否勾选）
 * @param notes          规范化说明：被丢弃的越界时间、去重提示等，需如实展示
 */
public record AiCandidateResponse(
        String materialId,
        String subtitleSource,
        String subtitleFile,
        int totalCues,
        int cuesUsed,
        boolean truncated,
        String model,
        long elapsedMs,
        List<AiCaseCandidate> candidates,
        int duplicateCount,
        List<String> notes
) {
}
