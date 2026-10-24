package com.liveproof.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 素材对外视图。
 *
 * <p>不含任何绝对路径、目录、密钥；{@code originalName} 仅用于展示，永不参与路径拼接。
 *
 * @param storage   local（内置示例，走 /media 与 FFmpeg）/ polyv（保利威点播）
 * @param status    transcoding / ready / failed / deleted
 * @param published 保利威是否已发布（60/61）；{@code status=50 等待审核} 时为 false，
 *                  但 playUrl 已可播，前端据此提示而不是阻塞
 * @param srtFileName          仅表示「用户为该素材上传过 SRT」的登记值，内置示例素材为 null
 * @param subtitleAvailable    <b>该素材现在能否解析到字幕</b>（三级解析顺序的结果，见
 *                             {@code MaterialService#subtitleAvailable}）。不能拿
 *                             {@code srtFileName != null} 代替：内置示例素材的登记值为 null，
 *                             但通过静态白名单映射到配套 SRT，实际有字幕；直接用登记值判断
 *                             会让首页把「有字幕的素材」标成「无字幕」，与点进去后的实际表现矛盾
 * @param createdAt            素材登记时间（上传或内置导入）
 * @param updatedAt            素材最后变更时间（转码状态、字幕登记都会刷新）。用于首页
 *                             「继续核验」排序；<b>不代表真正编辑过</b>，文案须能区分
 *                             「上次编辑」与「创建于」
 */
public record MaterialDto(
        String id,
        String storage,
        String originalName,
        String fileName,
        boolean builtin,
        String status,
        Integer polyvStatus,
        boolean published,
        BigDecimal durationSec,
        Long sizeBytes,
        Integer width,
        Integer height,
        String srtFileName,
        boolean subtitleAvailable,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
