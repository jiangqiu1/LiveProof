package com.liveproof.dto;

import com.liveproof.enums.AnswerStatus;
import com.liveproof.enums.EvidenceSufficiency;
import com.liveproof.enums.EvidenceType;
import com.liveproof.enums.ReviewStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * 改判请求体。四个枚举字段全部可选、彼此独立：
 * 只更新请求里出现的字段，未出现的字段保持原值，绝不联动推导。
 *
 * <p>JSON 中显式传 {@code null} 与不传等价（视为未提供）。
 *
 * <p>阶段 2 追加 {@code reasoning}（核验依据）与 {@code limitations}（局限与风险）两个自由文本字段。
 * 这是<b>追加</b>而非改变既有语义：同样"出现才写"、同样不参与任何推导。
 * 对应数据库列在阶段 1 已存在（{@code cases.reasoning} / {@code cases.limitations}），无需数据库迁移。
 *
 * <p>阶段 3 追加 {@code timePoints}（人工标记的关联时间点，单位秒），语义三态：
 * <ul>
 *   <li>字段不出现（{@code null}）→ 保持库中原值；</li>
 *   <li>传空数组 {@code []} → 清空全部标记；</li>
 *   <li>传数值数组 → 整体替换（服务端升序去重后落库）。</li>
 * </ul>
 * 该字段与 {@code startSec}/{@code endSec}（数据源自带的演示区间）无关，二者不互相推导。
 */
public record CasePatchRequest(
        ReviewStatus reviewStatus,
        AnswerStatus answerStatus,
        EvidenceType evidenceType,
        EvidenceSufficiency evidenceSufficiency,
        String reasoning,
        String limitations,
        List<BigDecimal> timePoints
) {

    /** 自由文本上限，与前端输入框计数保持一致。 */
    public static final int MAX_TEXT_LENGTH = 1000;

    /** 单个用例可标记的时间点数量上限，与前端提示保持一致。 */
    public static final int MAX_TIME_POINTS = 20;

    /** 时间点上界（秒）：2 小时，覆盖任何演示回放的同时挡住明显错误的值。 */
    public static final BigDecimal MAX_TIME_POINT_SEC = BigDecimal.valueOf(7200);

    public boolean isEmpty() {
        return reviewStatus == null
                && answerStatus == null
                && evidenceType == null
                && evidenceSufficiency == null
                && reasoning == null
                && limitations == null
                && timePoints == null;
    }
}
