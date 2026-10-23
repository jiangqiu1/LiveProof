package com.liveproof.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.liveproof.enums.AnswerStatus;
import com.liveproof.enums.EvidenceSufficiency;
import com.liveproof.enums.EvidenceType;
import com.liveproof.enums.ReviewStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 核验用例（表 cases）。
 *
 * <p>四类核验字段 reviewStatus / answerStatus / evidenceType / evidenceSufficiency
 * 各自独立，本实体不提供任何互相推导的 setter。
 *
 * <p>evidenceStatus 为旧模型字段，只读保留（迁移期不删列），对外以 legacyEvidenceStatus 暴露。
 */
@Getter
@Setter
@TableName("cases")
public class CaseEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String ds;
    private String question;
    private String claim;
    private String video;

    /** 所属素材（materials.id）。历史行为 NULL，由 V7 回填内置素材。 */
    private String materialId;

    /** sample / manual / csv —— 只用于区分来源，不参与任何推导。 */
    private String source;

    /** 创建时间；历史行为 NULL（不伪造迁移时刻）。 */
    private LocalDateTime createdAt;

    private BigDecimal startSec;
    private BigDecimal endSec;

    private AnswerStatus answerStatus;

    /** 旧字段：证据形态与充分性混在一个枚举里，迁移后不再写入。 */
    private String evidenceStatus;

    private String reasoning;
    private String limitations;

    /**
     * 人工标记的关联时间点（秒），JSON 数值数组；NULL 表示未标记。
     * 与 start_sec / end_sec 的区别：那两个是数据源自带的区间，本字段是核验人补充的定位点。
     */
    private String timePoints;
    private Boolean followUpRequired;
    private String followUpTask;

    private String framesJson;
    private String rawJson;

    private LocalDateTime updatedAt;

    @TableField("review_status")
    private ReviewStatus reviewStatus;

    @TableField("evidence_type")
    private EvidenceType evidenceType;

    @TableField("evidence_sufficiency")
    private EvidenceSufficiency evidenceSufficiency;
}
