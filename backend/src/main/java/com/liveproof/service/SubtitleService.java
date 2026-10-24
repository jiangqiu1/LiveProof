package com.liveproof.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.liveproof.config.MaterialProperties;
import com.liveproof.config.MediaProperties;
import com.liveproof.dto.SubtitleCue;
import com.liveproof.dto.SubtitleResponse;
import com.liveproof.entity.MaterialEntity;
import com.liveproof.exception.NotFoundException;
import com.liveproof.exception.ApiException;
import com.liveproof.mapper.MaterialMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 字幕解析：SRT -> cue 列表（时间统一为秒）。
 *
 * <p>字幕来源有三种，按优先级：
 * <ol>
 *   <li>素材登记的上传字幕（{@code liveproof.materials.upload-dir}/{素材id}.srt）
 *       —— 用户在素材页显式上传的，必须最优先，否则界面显示「已上传」而核验用的仍是内置字幕</li>
 *   <li>静态白名单映射（内置示例素材配套的 SRT）</li>
 *   <li>没有则 404，由上层决定是否降级为「无字幕」</li>
 * </ol>
 *
 * <p>{@code videoName} 有两种合法写法：云端素材传素材 id，内置素材传白名单文件名。
 * 两种情况都要能解析到素材行，否则「上传的 SRT 只对其中一种写法生效」。
 *
 * <p>这里<b>不</b>直接依赖 {@code MaterialService}（后者上传 SRT 时要调 {@link #parse(String)}，
 * 注入服务会形成循环依赖），只读 {@code MaterialMapper} 与素材目录。
 * 不调用任何 ASR / 大模型。
 */
@Service
public class SubtitleService {

    private static final Logger log = LoggerFactory.getLogger(SubtitleService.class);

    private final MediaProperties mediaProperties;
    private final MaterialProperties materialProperties;
    private final MaterialMapper materialMapper;

    public SubtitleService(MediaProperties mediaProperties,
                           MaterialProperties materialProperties,
                           MaterialMapper materialMapper) {
        this.mediaProperties = mediaProperties;
        this.materialProperties = materialProperties;
        this.materialMapper = materialMapper;
    }

    public SubtitleResponse forVideo(String videoName) {
        // 1. 素材登记的字幕最优先：用户显式上传的东西不能被内置映射悄悄顶掉，
        //    否则素材页显示「已上传 SRT」，而工作台与 AI 核验拿到的仍是另一份字幕。
        MaterialEntity material = resolveMaterial(videoName);
        if (material != null && material.getSrtFileName() != null) {
            Path root = materialProperties.root();
            Path path = root.resolve(material.getSrtFileName()).normalize();
            if (!path.startsWith(root) || !Files.isRegularFile(path)) {
                // 登记了但文件不在：如实报错，绝不用静态字幕冒充用户上传的那份
                log.warn("素材 {} 登记了字幕 {}，但文件不存在", material.getId(), material.getSrtFileName());
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SUBTITLE_READ_FAILED",
                        "素材登记的字幕文件缺失：" + material.getSrtFileName());
            }
            return read(videoName, material.getSrtFileName(), path, SubtitleResponse.SOURCE_UPLOADED);
        }

        // 2. 静态白名单映射（内置示例素材配套的 SRT）
        Optional<String> mapped = mediaProperties.subtitleNameOf(videoName);
        if (mapped.isPresent()) {
            Path path = mediaProperties.resolveSubtitle(mapped.get())
                    .orElseThrow(() -> NotFoundException.subtitleNotFound(videoName));
            return read(videoName, mapped.get(), path, SubtitleResponse.SOURCE_MAPPING);
        }

        // 3. 兼容旧写法：文件名固定为 {标识}.srt，只接受安全字符，不接受任何路径片段
        String materialSrt = videoName == null ? null : videoName + ".srt";
        if (materialSrt != null && materialSrt.matches("^[A-Za-z0-9_\\-]{1,120}\\.srt$")) {
            Path root = materialProperties.root();
            Path path = root.resolve(materialSrt).normalize();
            if (path.startsWith(root) && Files.isRegularFile(path)) {
                return read(videoName, materialSrt, path, SubtitleResponse.SOURCE_LEGACY);
            }
        }
        throw NotFoundException.subtitleNotFound(videoName);
    }

    /**
     * 视频标识 → 素材行：云端素材传素材 id，内置素材传白名单文件名。
     *
     * <p>只用于解析字幕归属，查不到返回 null（回退到静态映射），不抛错。
     */
    private MaterialEntity resolveMaterial(String videoName) {
        if (videoName == null || videoName.isBlank()) {
            return null;
        }
        MaterialEntity byId = materialMapper.selectById(videoName);
        if (byId != null) {
            return byId;
        }
        LambdaQueryWrapper<MaterialEntity> wrapper = Wrappers.lambdaQuery(MaterialEntity.class)
                .eq(MaterialEntity::getFileName, videoName)
                .last("LIMIT 1");
        return materialMapper.selectOne(wrapper);
    }

    private SubtitleResponse read(String videoName, String subtitleName, Path path, String source) {
        List<SubtitleCue> cues;
        try {
            cues = parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("读取字幕失败：{}", path, e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SUBTITLE_READ_FAILED", "字幕读取失败");
        }
        return new SubtitleResponse(videoName, subtitleName, source, "zh", cues.size(), cues);
    }

    /** 解析 SRT 文本。容忍 BOM、CRLF、多余空行与缺失序号。 */
    public static List<SubtitleCue> parse(String raw) {
        String text = raw.startsWith("\uFEFF") ? raw.substring(1) : raw;
        text = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (text.isEmpty()) {
            return List.of();
        }

        List<SubtitleCue> cues = new ArrayList<>();
        String[] blocks = text.split("\n\\s*\n");
        int fallbackIndex = 0;
        for (String block : blocks) {
            String[] lines = block.split("\n", -1);
            int timeLine = -1;
            for (int i = 0; i < lines.length && i < 2; i++) {
                if (lines[i].contains("-->")) {
                    timeLine = i;
                    break;
                }
            }
            if (timeLine < 0) {
                continue;
            }
            String[] bounds = lines[timeLine].split("-->");
            if (bounds.length != 2) {
                continue;
            }
            double start = parseTimestamp(bounds[0]);
            double end = parseTimestamp(bounds[1]);
            if (start < 0 || end < 0) {
                continue;
            }

            int index = ++fallbackIndex;
            if (timeLine > 0) {
                try {
                    index = Integer.parseInt(lines[0].trim());
                    fallbackIndex = index;
                } catch (NumberFormatException ignored) {
                    // 序号缺失时沿用递增编号
                }
            }

            StringBuilder body = new StringBuilder();
            for (int i = timeLine + 1; i < lines.length; i++) {
                if (body.length() > 0) {
                    body.append('\n');
                }
                body.append(lines[i]);
            }
            cues.add(new SubtitleCue(index, start, end, body.toString().trim()));
        }
        return cues;
    }

    /** "00:00:00,481" -> 0.481；解析失败返回 -1。 */
    static double parseTimestamp(String raw) {
        String value = raw.trim().replace(',', '.');
        String[] parts = value.split(":");
        try {
            if (parts.length == 3) {
                return Integer.parseInt(parts[0].trim()) * 3600
                        + Integer.parseInt(parts[1].trim()) * 60
                        + Double.parseDouble(parts[2].trim());
            }
            if (parts.length == 2) {
                return Integer.parseInt(parts[0].trim()) * 60
                        + Double.parseDouble(parts[1].trim());
            }
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
