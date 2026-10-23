package com.liveproof.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.liveproof.enums.SmartSubtitleTaskStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 保利威智能字幕任务登记（表 polyv_subtitle_tasks）。
 *
 * <p>只存任务元数据，不存字幕正文；也不存任何密钥或签名。一个 (video, vid) 只有一行，
 * 由唯一键 uk_polyv_video_vid 保证。
 */
@Getter
@Setter
@TableName("polyv_subtitle_tasks")
public class PolyvSubtitleTaskEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String video;
    private String vid;
    private String taskId;
    private String language;
    private SmartSubtitleTaskStatus status;
    private BigDecimal videoDurationSec;
    private Integer subtitleCount;
    private LocalDateTime createdAt;
    private LocalDateTime lastCheckedAt;
    private LocalDateTime lastSuccessAt;
    private String errorCode;
    private String errorMessage;
}
