package com.liveproof.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.liveproof.config.MaterialProperties;
import com.liveproof.config.MediaProperties;
import com.liveproof.config.PolyvProperties;
import com.liveproof.dto.MaterialDto;
import com.liveproof.dto.MaterialPlayDto;
import com.liveproof.dto.SubtitleCue;
import com.liveproof.entity.CaseEntity;
import com.liveproof.entity.MaterialEntity;
import com.liveproof.exception.MaterialErrors;
import com.liveproof.exception.NotFoundException;
import com.liveproof.mapper.CaseMapper;
import com.liveproof.mapper.MaterialMapper;
import com.liveproof.polyv.PolyvVodClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 素材库。
 *
 * <p>存储边界：视频本体在保利威点播，本机目录只放 SRT 与上传中转临时文件。
 * 任何对外响应都不含绝对路径。
 *
 * <p>状态映射（实测依据见 {@link PolyvVodClient}）：
 * <ul>
 *   <li>10/20 → {@code transcoding}</li>
 *   <li>50 → {@code ready}（等待审核，但 playUrl 已可播，前端提示而非阻塞）</li>
 *   <li>60/61 → {@code ready} 且 {@code published=true}</li>
 *   <li>40/51 → {@code failed}；-1 → {@code deleted}</li>
 * </ul>
 */
@Service
public class MaterialService {

    private static final Logger log = LoggerFactory.getLogger(MaterialService.class);

    private static final String BUILTIN_ID = "mat_builtin_demo";

    private final MaterialMapper materialMapper;
    private final CaseMapper caseMapper;
    private final MaterialProperties materialProperties;
    private final PolyvProperties polyvProperties;
    private final PolyvVodClient vodClient;
    private final MediaProperties mediaProperties;

    public MaterialService(MaterialMapper materialMapper,
                           CaseMapper caseMapper,
                           MaterialProperties materialProperties,
                           PolyvProperties polyvProperties,
                           PolyvVodClient vodClient,
                           MediaProperties mediaProperties) {
        this.materialMapper = materialMapper;
        this.caseMapper = caseMapper;
        this.materialProperties = materialProperties;
        this.polyvProperties = polyvProperties;
        this.vodClient = vodClient;
        this.mediaProperties = mediaProperties;
    }

    /** 素材列表：内置在前，其余按创建时间倒序；顺手刷新仍在转码的行。 */
    public List<MaterialDto> list() {
        LambdaQueryWrapper<MaterialEntity> wrapper = Wrappers.lambdaQuery(MaterialEntity.class);
        wrapper.orderByDesc(MaterialEntity::getBuiltin).orderByDesc(MaterialEntity::getCreatedAt);
        return materialMapper.selectList(wrapper).stream()
                .map(this::refreshIfTranscoding)
                .map(this::toDto)
                .toList();
    }

    /**
     * 只读列举，<b>不触发任何云端刷新</b>。
     *
     * <p>供「接入状态」这类声明不产生外部调用的接口使用：状态接口若顺手刷新转码进度，
     * 就不再是只读语义。
     */
    public List<MaterialDto> listWithoutRefresh() {
        LambdaQueryWrapper<MaterialEntity> wrapper = Wrappers.lambdaQuery(MaterialEntity.class);
        wrapper.orderByDesc(MaterialEntity::getBuiltin).orderByDesc(MaterialEntity::getCreatedAt);
        return materialMapper.selectList(wrapper).stream().map(this::toDto).toList();
    }

    public MaterialDto get(String id) {
        MaterialEntity entity = require(id);
        return toDto(refreshIfNeeded(entity));
    }

    /** 素材实体（供其他服务解析归属用，不对外）。 */
    public MaterialEntity require(String id) {
        MaterialEntity entity = materialMapper.selectById(id);
        if (entity == null) {
            throw MaterialErrors.notFound(id);
        }
        return entity;
    }

    /**
     * 按「引用」解析素材：先按 id，再按本地文件名。
     *
     * <p>{@code cases.video} 对内置素材是文件名、对云端素材是素材 id，
     * 因此两种写法都要能解析。解析不到返回 null（由调用方决定降级行为）。
     */
    public MaterialEntity findByRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        MaterialEntity byId = materialMapper.selectById(ref);
        if (byId != null) {
            return byId;
        }
        LambdaQueryWrapper<MaterialEntity> wrapper = Wrappers.lambdaQuery(MaterialEntity.class)
                .eq(MaterialEntity::getFileName, ref)
                .last("LIMIT 1");
        return materialMapper.selectOne(wrapper);
    }

    /**
     * 上传视频到保利威。
     *
     * @return 已落库的素材（status 通常为 transcoding）
     */
    public MaterialDto upload(MultipartFile file) {
        if (!polyvProperties.uploadConfigured()) {
            throw MaterialErrors.uploadNotConfigured();
        }
        long count = materialMapper.selectCount(null);
        if (count >= materialProperties.getMaxCount()) {
            throw MaterialErrors.limitExceeded(materialProperties.getMaxCount());
        }
        validateMp4(file);

        Path temp = null;
        try {
            temp = Files.createTempFile("liveproof-material-", ".mp4");
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            String title = buildTitle(file.getOriginalFilename());
            PolyvVodClient.UploadResult uploaded = vodClient.upload(temp, title);

            MaterialEntity entity = new MaterialEntity();
            entity.setId("mat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            entity.setStorage("polyv");
            entity.setPolyvVid(uploaded.vid());
            entity.setFileName(null);
            entity.setOriginalName(trimTo(file.getOriginalFilename(), 180));
            entity.setPolyvStatus(uploaded.status());
            entity.setStatus(mapStatus(uploaded.status()));
            entity.setDurationSec(uploaded.durationSec() == null ? null : BigDecimal.valueOf(uploaded.durationSec()));
            entity.setSizeBytes(file.getSize());
            entity.setBuiltin(false);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            materialMapper.insert(entity);
            log.info("素材登记：id={} vid={} status={}", entity.getId(), entity.getPolyvVid(), entity.getStatus());
            return toDto(entity);
        } catch (IOException e) {
            throw MaterialErrors.invalidFile("读取上传文件失败");
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时文件中转用，删不掉交给系统清理
                }
            }
        }
    }

    /** 播放地址：每次实时向保利威取，不落库。 */
    public MaterialPlayDto play(String id) {
        MaterialEntity entity = require(id);
        if (entity.isLocal()) {
            throw MaterialErrors.notReady(id, entity.getStatus() + "（内置素材走 /media 路径）");
        }
        entity = refreshIfNeeded(entity);
        if (!"ready".equals(entity.getStatus())) {
            throw MaterialErrors.notReady(id, entity.getStatus());
        }
        if (entity.getPolyvVid() == null) {
            throw MaterialErrors.notReady(id, "缺少 vid");
        }
        PolyvVodClient.VideoInfo info = vodClient.videoInfo(entity.getPolyvVid());
        if (info.playUrl() == null) {
            throw MaterialErrors.notReady(id, "保利威尚未给出播放地址");
        }
        return new MaterialPlayDto(id, info.playUrl(), entity.getDurationSec(), info.published());
    }

    /** 删除素材：内置拒绝；被核验事项引用拒绝。 */
    public void delete(String id) {
        MaterialEntity entity = require(id);
        if (Boolean.TRUE.equals(entity.getBuiltin())) {
            throw MaterialErrors.builtin();
        }
        Long used = caseMapper.selectCount(Wrappers.lambdaQuery(CaseEntity.class)
                .eq(CaseEntity::getMaterialId, id));
        if (used != null && used > 0) {
            throw MaterialErrors.inUse(used.intValue());
        }
        if (entity.getSrtFileName() != null) {
            deleteSrtFile(entity.getSrtFileName());
        }
        materialMapper.deleteById(id);
        log.info("素材删除：id={} vid={}（云端视频保留，需在控制台处理）", id, entity.getPolyvVid());
    }

    /** 上传 SRT：只做格式与时间轴校验，文件落本机（证据定位要按 cue 检索）。 */
    public MaterialDto uploadSrt(String id, MultipartFile file) {
        MaterialEntity entity = require(id);
        if (file == null || file.isEmpty()) {
            throw MaterialErrors.invalidFile("字幕文件为空");
        }
        if (file.getSize() > materialProperties.getSrtMaxBytes()) {
            throw MaterialErrors.invalidFile("字幕文件超过 " + (materialProperties.getSrtMaxBytes() / 1024) + " KB");
        }
        String raw;
        try {
            raw = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw MaterialErrors.invalidFile("读取字幕文件失败");
        }
        List<SubtitleCue> cues = SubtitleService.parse(raw);
        if (cues.isEmpty()) {
            throw MaterialErrors.invalidFile("字幕文件解析不到任何条目，请确认是标准 SRT");
        }
        String fileName = entity.getId() + ".srt";
        Path target = materialProperties.root().resolve(fileName);
        try {
            Files.writeString(target, raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw MaterialErrors.invalidFile("保存字幕文件失败");
        }
        entity.setSrtFileName(fileName);
        entity.setUpdatedAt(LocalDateTime.now());
        materialMapper.updateById(entity);
        log.info("字幕登记：素材={} 条数={}", id, cues.size());
        return toDto(entity);
    }

    /**
     * 移除已上传的 SRT。
     *
     * <p>必须用显式 {@code set(..., null)} 的更新包装来清列：MyBatis-Plus 的
     * {@code updateById} 默认跳过 null 字段，直接 {@code setSrtFileName(null)} 再
     * {@code updateById} 只会在响应里看起来清掉了，数据库里 {@code srt_file_name} 仍留着旧值。
     * 后果是「文件已删、登记还在」，字幕解析会拿着一个不存在的文件名报错。
     */
    public MaterialDto deleteSrt(String id) {
        MaterialEntity entity = require(id);
        if (entity.getSrtFileName() != null) {
            deleteSrtFile(entity.getSrtFileName());
            materialMapper.update(null, Wrappers.lambdaUpdate(MaterialEntity.class)
                    .eq(MaterialEntity::getId, id)
                    .set(MaterialEntity::getSrtFileName, null)
                    .set(MaterialEntity::getUpdatedAt, LocalDateTime.now()));
            entity.setSrtFileName(null);
            entity.setUpdatedAt(LocalDateTime.now());
        }
        return toDto(entity);
    }

    /** 解析本机字幕文件（供 SubtitleService 兜底）。 */
    public Path srtPath(MaterialEntity entity) {
        if (entity.getSrtFileName() == null) {
            return null;
        }
        Path path = materialProperties.root().resolve(entity.getSrtFileName()).normalize();
        if (!path.startsWith(materialProperties.root()) || !Files.isRegularFile(path)) {
            return null;
        }
        return path;
    }

    // ── 内部 ─────────────────────────────────────────────

    private void validateMp4(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw MaterialErrors.invalidFile("请选择要上传的视频文件");
        }
        if (file.getSize() > materialProperties.getMaxBytes()) {
            throw MaterialErrors.invalidFile(
                    "视频超过上限 " + (materialProperties.getMaxBytes() / 1024 / 1024) + " MB");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
            throw MaterialErrors.invalidFile("只支持 mp4 格式");
        }
        byte[] head = new byte[12];
        try (InputStream in = file.getInputStream()) {
            int read = in.readNBytes(head, 0, head.length);
            if (read < 12) {
                throw MaterialErrors.invalidFile("文件内容为空或不完整");
            }
        } catch (IOException e) {
            throw MaterialErrors.invalidFile("读取文件失败");
        }
        String ftyp = new String(head, 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
        if (!"ftyp".equals(ftyp)) {
            throw MaterialErrors.invalidFile("文件不是合法的 mp4（缺少 ftyp 标识）");
        }
    }

    /** 保利威标题：去扩展名，截断，避免把用户路径写进云端。 */
    private static String buildTitle(String originalName) {
        String base = originalName == null ? "未命名素材" : originalName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return trimTo(base, 80);
    }

    private static String trimTo(String text, int max) {
        if (text == null) {
            return "未命名素材";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private MaterialEntity refreshIfTranscoding(MaterialEntity entity) {
        return "transcoding".equals(entity.getStatus()) ? refresh(entity) : entity;
    }

    /** 转码中的行、或等待审核（50）的行需要刷新；已发布的行不再打接口。 */
    private MaterialEntity refreshIfNeeded(MaterialEntity entity) {
        if ("transcoding".equals(entity.getStatus())
                || (entity.getPolyvStatus() != null && entity.getPolyvStatus() == 50)) {
            return refresh(entity);
        }
        return entity;
    }

    private MaterialEntity refresh(MaterialEntity entity) {
        if (entity.isLocal() || entity.getPolyvVid() == null || !polyvProperties.vodConfigured()) {
            return entity;
        }
        PolyvVodClient.VideoInfo info;
        try {
            info = vodClient.videoInfo(entity.getPolyvVid());
        } catch (RuntimeException e) {
            log.warn("刷新素材状态失败：id={} 原因={}", entity.getId(), e.getMessage());
            return entity;
        }
        String status = mapStatus(info.status());
        boolean changed = !status.equals(entity.getStatus())
                || !java.util.Objects.equals(entity.getPolyvStatus(), info.status());
        entity.setStatus(status);
        entity.setPolyvStatus(info.status());
        if (info.durationSec() != null) {
            entity.setDurationSec(BigDecimal.valueOf(info.durationSec()));
        }
        if (info.sizeBytes() != null) {
            entity.setSizeBytes(info.sizeBytes());
        }
        entity.setWidth(info.width());
        entity.setHeight(info.height());
        if ("failed".equals(status)) {
            entity.setErrorMessage("保利威处理失败（状态码 " + info.status() + "）");
        }
        entity.setUpdatedAt(LocalDateTime.now());
        if (changed) {
            materialMapper.updateById(entity);
        }
        return entity;
    }

    static String mapStatus(int polyvStatus) {
        return switch (polyvStatus) {
            case 10, 20 -> "transcoding";
            case 50, 60, 61 -> "ready";
            case 40, 51 -> "failed";
            case -1 -> "deleted";
            default -> "transcoding";
        };
    }

    private void deleteSrtFile(String fileName) {
        Path path = materialProperties.root().resolve(fileName).normalize();
        if (!path.startsWith(materialProperties.root())) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除字幕文件失败：{}", fileName);
        }
    }

    MaterialDto toDto(MaterialEntity entity) {
        boolean published = entity.getPolyvStatus() != null
                && (entity.getPolyvStatus() == 60 || entity.getPolyvStatus() == 61);
        return new MaterialDto(
                entity.getId(),
                entity.getStorage(),
                entity.getOriginalName(),
                entity.getFileName(),
                Boolean.TRUE.equals(entity.getBuiltin()),
                entity.getStatus(),
                entity.getPolyvStatus(),
                published,
                entity.getDurationSec(),
                entity.getSizeBytes(),
                entity.getWidth(),
                entity.getHeight(),
                entity.getSrtFileName(),
                subtitleAvailable(entity),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    /**
     * 该素材当前能否解析到字幕。
     *
     * <p>刻意复用 {@code SubtitleService#forVideo} 的<b>三级顺序</b>，只判断文件是否存在、不读内容
     * （列表接口要跑 N 次，读全文会把素材页拖慢）：
     * <ol>
     *   <li>素材行登记的 {@code srt_file_name}（用户上传的那份）；</li>
     *   <li>{@code media.subtitle-mapping} 静态白名单映射（内置示例素材配套的 SRT）；</li>
     *   <li>兼容旧写法 {@code {文件名}.srt}，只接受安全字符。</li>
     * </ol>
     *
     * <p><b>为什么不用 {@code srtFileName != null} 代替</b>：内置示例素材的登记值就是 null，
     * 它靠第 2 步映射到 {@code candidateflow-demo.srt}，实际有字幕。只判断登记值会让首页
     * 把有字幕的素材标成「无字幕」，用户点进去却看到字幕，属于前后不一致的假信息。
     *
     * <p>顺序不能颠倒：登记了但文件缺失时，{@code SubtitleService} 会直接报
     * {@code SUBTITLE_READ_FAILED} 而不回落到静态字幕（避免用别人的字幕冒充），
     * 所以这里也必须先看登记值、命中即返回 true，不能继续往下匹配。
     */
    private boolean subtitleAvailable(MaterialEntity entity) {
        String registered = entity.getSrtFileName();
        if (registered != null && !registered.isBlank()) {
            Path root = materialProperties.root();
            Path path = root.resolve(registered).normalize();
            return path.startsWith(root) && Files.isRegularFile(path);
        }

        String videoName = entity.getFileName();
        if (videoName == null || videoName.isBlank()) {
            return false;
        }
        Optional<String> mapped = mediaProperties.subtitleNameOf(videoName);
        if (mapped.isPresent() && mediaProperties.resolveSubtitle(mapped.get()).isPresent()) {
            return true;
        }

        String legacy = videoName + ".srt";
        if (legacy.matches("^[A-Za-z0-9_\\-]{1,120}\\.srt$")) {
            Path root = materialProperties.root();
            Path path = root.resolve(legacy).normalize();
            return path.startsWith(root) && Files.isRegularFile(path);
        }
        return false;
    }

    /** 内置示例素材 id（历史 cases 的归属）。 */
    public static String builtinId() {
        return BUILTIN_ID;
    }

    /** 供报告/核验按素材筛选时使用：确认素材存在。 */
    public MaterialEntity requireForQuery(String id) {
        MaterialEntity entity = require(id);
        if (!"ready".equals(entity.getStatus())) {
            throw NotFoundException.caseNotFound(id);
        }
        return entity;
    }

    /** 已就绪素材的时长（AI 判断窗口上限用）。 */
    public BigDecimal durationOf(MaterialEntity entity) {
        return entity.getDurationSec() == null ? null
                : entity.getDurationSec().setScale(3, RoundingMode.HALF_UP);
    }
}
