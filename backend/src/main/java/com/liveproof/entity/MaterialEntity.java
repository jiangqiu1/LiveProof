package com.liveproof.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 素材（表 materials）。
 *
 * <p>两种存储：
 * <ul>
 *   <li>{@code storage=local}：本机内置示例，走 {@code /media/{fileName}} + FFmpeg 抽帧</li>
 *   <li>{@code storage=polyv}：用户上传，视频在保利威点播，{@code polyvVid} 是唯一视频标识</li>
 * </ul>
 *
 * <p>{@code status} 只表达「能不能用」，字幕状态不合并进来：
 * 本地字幕看 {@code srtFileName} 是否为空，云端字幕看 {@code polyv_subtitle_tasks}。
 */
@Getter
@Setter
@TableName("materials")
public class MaterialEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    /** local / polyv。 */
    private String storage;

    private String polyvVid;

    private String fileName;

    private String originalName;

    private String srtFileName;

    private BigDecimal durationSec;

    private Integer polyvStatus;

    private Long sizeBytes;

    private Integer width;

    private Integer height;

    /** transcoding / ready / failed / deleted / rejected。 */
    private String status;

    private Boolean builtin;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public boolean isLocal() {
        return "local".equals(storage);
    }

    public boolean isReady() {
        return "ready".equals(status);
    }
}
