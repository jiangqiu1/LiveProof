package com.liveproof.dto;

import java.math.BigDecimal;

/**
 * AI 建议中的单条候选证据。
 *
 * <p>时间区间全部经过服务端校验：必须落在真实视频时长内，且与已提交给模型的字幕或关键帧对应。
 *
 * @param subtitle     该片段对应的字幕原文（模型无法引用时为 null）
 * @param observation  关键帧上的画面观察（没有可用画面时为 null）
 * @param supports     该片段能支持的结论
 * @param cannotProve  该片段不能证明的部分
 * @param outsideWindow 是否位于本条事项的原始时间区间之外（由服务端比对得出，不采信模型自述）
 * @param frameSec     对应关键帧的时间点，没有对应帧时为 null
 */
public record AiEvidenceItem(
        BigDecimal startSec,
        BigDecimal endSec,
        String subtitle,
        String observation,
        String supports,
        String cannotProve,
        boolean outsideWindow,
        BigDecimal frameSec
) {
}
