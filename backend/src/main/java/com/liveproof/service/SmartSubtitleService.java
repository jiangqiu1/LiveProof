package com.liveproof.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.liveproof.config.MediaProperties;
import com.liveproof.config.PolyvProperties;
import com.liveproof.dto.SmartSubtitleCreateRequest;
import com.liveproof.dto.SmartSubtitleCuesDto;
import com.liveproof.dto.SmartSubtitleTaskDto;
import com.liveproof.dto.SmartSubtitleStatusDto;
import com.liveproof.dto.SubtitleCue;
import com.liveproof.entity.PolyvSubtitleTaskEntity;
import com.liveproof.enums.SmartSubtitleTaskStatus;
import com.liveproof.enums.SubtitleSource;
import com.liveproof.exception.ApiException;
import com.liveproof.exception.BadRequestException;
import com.liveproof.exception.PolyvErrors;
import com.liveproof.mapper.PolyvSubtitleTaskMapper;
import com.liveproof.polyv.PolyvSubtitleClient;
import com.liveproof.polyv.PolyvSubtitleSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 保利威智能字幕业务：登记、创建（防重复）、查询状态、取回规范化字幕。
 *
 * <p>核心规则：
 * <ul>
 *   <li><b>不重复创建</b>：同一 (video, vid) 已有可复用任务（created/processing/ready）时直接返回，
 *       数据库唯一键 {@code uk_polyv_video_vid} 是并发下的最终保障</li>
 *   <li><b>可登记既有任务</b>：{@link #adopt} 先用只读接口校验任务真实存在，再写库，
 *       用于把阶段一已经创建的真实任务纳入管理，避免为了测试再花一次资源点</li>
 *   <li><b>不放弃校验</b>：字幕必须通过 {@link SmartSubtitleNormalizer} 的严格校验才允许对外返回；
 *       校验失败写 failed 状态与脱敏原因，并如实抛 422</li>
 *   <li><b>不泄露凭据</b>：错误信息统一经 {@link #sanitize} 脱敏后才落库/返回</li>
 * </ul>
 *
 * <p>本类不使用类级事务：失败状态（failed / not_found）需要在抛异常之前持久化，
 * 放在同一事务里会被回滚，导致"报错了但库里还是旧状态"。单条 insert/update 本身是原子的。
 */
@Service
public class SmartSubtitleService {

    private static final Logger log = LoggerFactory.getLogger(SmartSubtitleService.class);

    /** 登记时间最多允许回溯 30 天，防止写入明显错误的创建时间。 */
    private static final Duration ADOPT_CREATED_AT_RETENTION = Duration.ofDays(30);

    /** 允许的时钟偏差。 */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private static final int ERROR_MESSAGE_MAX = 200;

    private final PolyvSubtitleClient client;
    private final PolyvProperties props;
    private final MediaProperties media;
    private final PolyvSubtitleTaskMapper taskMapper;
    private final SmartSubtitleNormalizer normalizer;
    private final MaterialService materialService;

    public SmartSubtitleService(PolyvSubtitleClient client,
                               PolyvProperties props,
                               MediaProperties media,
                               PolyvSubtitleTaskMapper taskMapper,
                               SmartSubtitleNormalizer normalizer,
                               MaterialService materialService) {
        this.client = client;
        this.props = props;
        this.media = media;
        this.taskMapper = taskMapper;
        this.normalizer = normalizer;
        this.materialService = materialService;
    }

    // ------------------------------------------------------------------ 状态

    /**
     * 接入状态：只读，不发起任何云端调用，也不回显凭据。
     *
     * <p>视频清单 = 静态白名单（内置示例）+ 素材库里已就绪的云端素材。
     * 内置两条映射到同一个 VID；云端素材的 VID 来自上传时的登记值。
     */
    public SmartSubtitleStatusDto status() {
        List<SmartSubtitleStatusDto.VideoTarget> videos = new java.util.ArrayList<>(
                media.getAllowedFiles().stream()
                        .map(video -> new SmartSubtitleStatusDto.VideoTarget(
                                video,
                                props.vidOf(video).orElse(null),
                                props.durationOf(video).orElse(null),
                                props.vidOf(video).isPresent()))
                        .toList());
        for (var material : materialService.listWithoutRefresh()) {
            if (material.builtin() || !"ready".equals(material.status())) {
                continue;
            }
            videos.add(new SmartSubtitleStatusDto.VideoTarget(
                    material.id(),
                    materialVid(material.id()),
                    material.durationSec() == null ? null : material.durationSec().doubleValue(),
                    materialVid(material.id()) != null));
        }
        final List<SmartSubtitleStatusDto.VideoTarget> targets = videos;
        String note;
        if (!props.configured()) {
            note = "未配置凭据：设置 POLYV_LIVE_APP_ID / POLYV_LIVE_APP_SECRET 后重启服务";
        } else if (targets.stream().noneMatch(SmartSubtitleStatusDto.VideoTarget::vidConfigured)) {
            note = "凭据已配置，但没有视频配置 VID，智能字幕接口不可用";
        } else {
            note = "已配置，可创建或登记智能字幕任务；所有字幕仍须人工核对后才可作为核验依据";
        }
        return new SmartSubtitleStatusDto(
                props.configured(),
                props.host(),
                props.getDefaultLanguage(),
                props.getAllowedLanguages(),
                targets,
                note);
    }

    /**
     * 本地登记列表（不查云端）。
     *
     * <p>按视频过滤时，除了精确匹配，还会带上<b>同一 VID</b> 的登记行：
     * 同一段演示的两个本地素材（烧录字幕版 / 无字幕版）共用同一个云端视频，
     * 任务只登记一次，两个素材都要能查到它。
     */
    public List<SmartSubtitleTaskDto> list(String video) {
        LambdaQueryWrapper<PolyvSubtitleTaskEntity> wrapper = Wrappers.lambdaQuery(PolyvSubtitleTaskEntity.class);
        if (video != null && !video.isBlank()) {
            String target = video.trim();
            String sharedVid = vidOf(target);
            wrapper.and(scope -> {
                scope.eq(PolyvSubtitleTaskEntity::getVideo, target);
                if (sharedVid != null && !sharedVid.isBlank()) {
                    scope.or().eq(PolyvSubtitleTaskEntity::getVid, sharedVid);
                }
            });
        }
        wrapper.orderByDesc(PolyvSubtitleTaskEntity::getCreatedAt).orderByDesc(PolyvSubtitleTaskEntity::getId);
        return taskMapper.selectList(wrapper).stream().map(entity -> toDto(entity, null)).toList();
    }

    // ------------------------------------------------------------------ 创建

    /**
     * 创建任务，或复用该视频已有的有效任务。
     *
     * @return {@code reused=true} 表示沿用既有任务，本次没有调用创建接口（没有消耗资源点）
     */
    public SmartSubtitleTaskDto createOrReuse(SmartSubtitleCreateRequest request) {
        // 先判部署状态：凭据缺失是 503 部署问题，不应被"视频未映射"的 400 掩盖
        if (!props.configured()) {
            throw PolyvErrors.notConfigured();
        }
        Target target = resolveTarget(request.video());
        String language = resolveLanguage(request.language());

        PolyvSubtitleTaskEntity existing = findByVideoAndVid(target.video(), target.vid());
        if (reusable(existing)) {
            log.info("复用既有智能字幕任务 video={} taskId={} status={}",
                    target.video(), existing.getTaskId(), existing.getStatus());
            return toDto(existing, true);
        }

        // 同一 VID 已被另一个本地素材登记（同一段演示的两个版本）：直接复用，不再创建
        PolyvSubtitleTaskEntity shared = findByVid(target.vid());
        if (reusable(shared)) {
            log.info("复用同一 VID 的既有任务 video={} 请求视频={} taskId={}",
                    shared.getVideo(), target.video(), shared.getTaskId());
            return toDto(shared, true);
        }

        long taskId = client.createTask(target.vid(), language);

        PolyvSubtitleTaskEntity row = existing != null ? existing : new PolyvSubtitleTaskEntity();
        row.setVideo(target.video());
        row.setVid(target.vid());
        row.setTaskId(String.valueOf(taskId));
        row.setLanguage(language);
        row.setStatus(SmartSubtitleTaskStatus.CREATED);
        row.setVideoDurationSec(toDecimal(target.durationSec()));
        row.setSubtitleCount(null);
        row.setCreatedAt(LocalDateTime.now());
        row.setLastCheckedAt(null);
        row.setLastSuccessAt(null);
        row.setErrorCode(null);
        row.setErrorMessage(null);

        if (existing != null) {
            taskMapper.updateById(row);
            return toDto(row, false);
        }
        try {
            taskMapper.insert(row);
            return toDto(row, false);
        } catch (DuplicateKeyException e) {
            // 并发下另一请求先插入了同一 (video, vid)：回读并复用，不再创建
            PolyvSubtitleTaskEntity concurrent = findByVideoAndVid(target.video(), target.vid());
            if (concurrent != null && reusable(concurrent)) {
                log.warn("并发创建被唯一键拦截，改为复用 taskId={}", concurrent.getTaskId());
                return toDto(concurrent, true);
            }
            throw PolyvErrors.upstream("任务登记冲突，请重试");
        }
    }

    // ------------------------------------------------------------------ 登记既有任务

    /**
     * 登记一条已经存在的云端任务（不创建新任务）。
     *
     * <p>流程：只读查询验证任务存在 → 若是 READY 则先做字幕严格校验 → 通过后写库。
     * 校验不通过则不登记，避免把不可用的任务写进任务表。
     */
    public SmartSubtitleTaskDto adopt(com.liveproof.dto.SmartSubtitleAdoptRequest request) {
        if (!props.configured()) {
            throw PolyvErrors.notConfigured();
        }
        Target target = resolveTarget(request.video());
        String language = resolveLanguage(request.language());
        String taskId = normalizeTaskId(request.taskId());

        PolyvSubtitleTaskEntity boundToOther = findByTaskId(taskId);
        if (boundToOther != null
                && !(target.video().equals(boundToOther.getVideo()) && target.vid().equals(boundToOther.getVid()))) {
            throw PolyvErrors.taskAlreadyBound(taskId, boundToOther.getVideo());
        }

        // 只读校验：56810 会在这里抛 404，不会写库
        PolyvSubtitleSnapshot snapshot = client.fetchTask(taskId);
        Integer subtitleCount = null;
        if (snapshot.state() == com.liveproof.polyv.PolyvTaskState.READY) {
            List<SubtitleCue> cues = normalizer.normalize(snapshot.cues(), target.durationSec());
            subtitleCount = cues.size();
        }

        PolyvSubtitleTaskEntity row = boundToOther != null ? boundToOther
                : findByVideoAndVid(target.video(), target.vid());
        boolean insert = row == null;
        if (insert) {
            row = new PolyvSubtitleTaskEntity();
            row.setVideo(target.video());
            row.setVid(target.vid());
            row.setVideoDurationSec(toDecimal(target.durationSec()));
        }
        row.setTaskId(taskId);
        row.setLanguage(language);
        LocalDateTime now = LocalDateTime.now();
        row.setLastCheckedAt(now);
        if (snapshot.state() == com.liveproof.polyv.PolyvTaskState.READY) {
            row.setStatus(SmartSubtitleTaskStatus.READY);
            row.setSubtitleCount(subtitleCount);
            row.setLastSuccessAt(now);
        } else {
            row.setStatus(SmartSubtitleTaskStatus.PROCESSING);
            row.setSubtitleCount(null);
        }
        row.setErrorCode(null);
        row.setErrorMessage(null);
        // createdAt 语义：平台的「任务创建时间」，由登记方补齐。
        // 重复登记（幂等更新）且请求未带 createdAt 时必须保留首次登记值，
        // 否则一次无参重放就会把真实创建时间冲成当前时间。
        if (request.createdAt() != null) {
            row.setCreatedAt(validateCreatedAt(request.createdAt()));
        } else if (insert || row.getCreatedAt() == null) {
            row.setCreatedAt(now);
        }

        try {
            if (insert) {
                taskMapper.insert(row);
            } else {
                taskMapper.updateById(row);
            }
        } catch (DuplicateKeyException e) {
            throw PolyvErrors.taskAlreadyBound(taskId, target.video());
        }
        log.info("已登记既有智能字幕任务 video={} taskId={} status={} cues={}",
                target.video(), taskId, row.getStatus(), row.getSubtitleCount());
        return toDto(row, false);
    }

    // ------------------------------------------------------------------ 状态查询

    /** 查询任务状态：会真实向云端复查一次（只读、不消耗资源点）并更新登记表。 */
    public SmartSubtitleTaskDto refresh(String rawTaskId) {
        String taskId = normalizeTaskId(rawTaskId);
        PolyvSubtitleTaskEntity row = requireLocalTask(taskId);
        if (!props.configured()) {
            throw PolyvErrors.notConfigured();
        }
        Double duration = durationOf(row);

        PolyvSubtitleSnapshot snapshot;
        try {
            snapshot = client.fetchTask(taskId);
        } catch (ApiException e) {
            persistFailure(row, e);
            throw e;
        }

        if (snapshot.state() == com.liveproof.polyv.PolyvTaskState.PROCESSING) {
            row.setStatus(SmartSubtitleTaskStatus.PROCESSING);
            row.setLastCheckedAt(LocalDateTime.now());
            row.setErrorCode(null);
            row.setErrorMessage(null);
            taskMapper.updateById(row);
            return toDto(row, null);
        }

        List<SubtitleCue> cues;
        try {
            cues = normalizer.normalize(snapshot.cues(), duration);
        } catch (ApiException e) {
            persistFailure(row, e);
            throw e;
        }
        applyReady(row, cues.size());
        taskMapper.updateById(row);
        return toDto(row, null);
    }

    // ------------------------------------------------------------------ 字幕

    /**
     * 取回规范化字幕并按时间区间重叠过滤。
     *
     * <p>云端仍是 PROCESSING 时返回 409，不返回空字幕冒充结果。
     */
    public SmartSubtitleCuesDto cues(String rawTaskId, Double from, Double to) {
        String taskId = normalizeTaskId(rawTaskId);
        validateWindow(from, to);
        PolyvSubtitleTaskEntity row = requireLocalTask(taskId);
        if (!props.configured()) {
            throw PolyvErrors.notConfigured();
        }
        Double duration = durationOf(row);

        PolyvSubtitleSnapshot snapshot;
        try {
            snapshot = client.fetchTask(taskId);
        } catch (ApiException e) {
            persistFailure(row, e);
            throw e;
        }
        if (snapshot.state() == com.liveproof.polyv.PolyvTaskState.PROCESSING) {
            row.setStatus(SmartSubtitleTaskStatus.PROCESSING);
            row.setLastCheckedAt(LocalDateTime.now());
            taskMapper.updateById(row);
            throw PolyvErrors.taskProcessing();
        }

        List<SubtitleCue> cues;
        try {
            cues = normalizer.normalize(snapshot.cues(), duration);
        } catch (ApiException e) {
            persistFailure(row, e);
            throw e;
        }
        applyReady(row, cues.size());
        taskMapper.updateById(row);

        List<SubtitleCue> windowed = SubtitleWindow.overlapping(cues, from, to);
        return new SmartSubtitleCuesDto(
                row.getVideo(),
                row.getVid(),
                taskId,
                SubtitleSource.CLOUD,
                row.getLanguage(),
                row.getStatus(),
                snapshot.subtitleName(),
                cues.size(),
                windowed.size(),
                from,
                to,
                windowed,
                row.getLastSuccessAt());
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 取某视频<b>已就绪</b>的云端字幕，供 AI 核验等上游链路按需调用。
     *
     * <p>不在这里创建任务：创建要消耗资源点，属于用户在素材页的显式动作。
     * 没有就绪任务时抛 409，让调用方明确知道「云端字幕还没准备好」，
     * 而不是悄悄退化成无字幕分析。
     *
     * @param from 可选时间窗起点（秒），null 表示不限
     * @param to   可选时间窗终点（秒），null 表示不限
     */
    public SmartSubtitleCuesDto readyCuesFor(String video, Double from, Double to) {
        String target = video == null ? "" : video.trim();
        // 先按精确视频标识找，再按 VID 兜底：同一个云端视频可能被两个本地素材共用
        //（同一段演示、时间轴一致，只有字幕叠层不同）
        PolyvSubtitleTaskEntity ready = findReadyRow(target);
        if (ready == null) {
            String vid = vidOf(target);
            if (vid != null && !vid.isBlank()) {
                ready = findByVid(vid);
                if (!isReady(ready)) {
                    ready = null;
                }
            }
        }
        if (ready == null) {
            throw PolyvErrors.subtitleNotReady(target);
        }
        return cues(ready.getTaskId(), from, to);
    }

    // ------------------------------------------------------------------ 内部

    private record Target(String video, String vid, Double durationSec) {
    }

    /**
     * 解析视频标识 → VID + 时长。
     *
     * <p>两种合法标识：
     * <ul>
     *   <li>静态白名单文件名（内置示例）→ 走配置映射</li>
     *   <li>素材 id（用户上传到保利威的素材）→ 走素材登记的 VID 与时长</li>
     * </ul>
     * 都不命中时按「视频不在媒体白名单内」拒绝，不猜 VID。
     */
    private Target resolveTarget(String rawVideo) {
        String video = rawVideo == null ? "" : rawVideo.trim();
        if (media.getAllowedFiles().contains(video)) {
            String vid = props.vidOf(video).orElseThrow(() -> PolyvErrors.vidNotConfigured(video));
            Double duration = props.durationOf(video).orElseThrow(() -> PolyvErrors.durationNotConfigured(video));
            return new Target(video, vid, duration);
        }
        var material = materialService.findByRef(video);
        if (material != null && !material.isLocal()) {
            if (!"ready".equals(material.getStatus())) {
                throw BadRequestException.invalidParameter("该素材尚未完成转码，暂不能创建智能字幕：" + video);
            }
            if (material.getPolyvVid() == null || material.getPolyvVid().isBlank()) {
                throw PolyvErrors.vidNotConfigured(video);
            }
            Double duration = material.getDurationSec() == null ? null : material.getDurationSec().doubleValue();
            if (duration == null || duration <= 0) {
                throw PolyvErrors.durationNotConfigured(video);
            }
            return new Target(video, material.getPolyvVid(), duration);
        }
        throw BadRequestException.invalidParameter("视频不在媒体白名单内：" + video);
    }

    /** 素材标识 → VID；内置/未知素材返回 null。 */
    private String materialVid(String video) {
        var material = materialService.findByRef(video);
        return material == null ? null : material.getPolyvVid();
    }

    /** 视频标识 → VID：静态配置优先，其次素材登记（云端素材）。 */
    private String vidOf(String video) {
        Optional<String> configured = props.vidOf(video);
        return configured.orElseGet(() -> materialVid(video));
    }

    private String resolveLanguage(String raw) {
        String language = raw == null || raw.isBlank() ? props.getDefaultLanguage() : raw.trim();
        if (!props.languageAllowed(language)) {
            throw BadRequestException.invalidParameter(
                    "不支持的字幕语言：" + language + "，允许值：" + props.getAllowedLanguages());
        }
        return language;
    }

    private static String normalizeTaskId(String raw) {
        String taskId = raw == null ? "" : raw.trim();
        if (!taskId.matches("^[0-9]{1,19}$")) {
            throw BadRequestException.invalidParameter("taskId 必须是纯数字");
        }
        return taskId;
    }

    private static void validateWindow(Double from, Double to) {
        if (from != null && (from < 0 || from.isNaN())) {
            throw BadRequestException.invalidParameter("from 必须是不小于 0 的秒数");
        }
        if (to != null && (to < 0 || to.isNaN())) {
            throw BadRequestException.invalidParameter("to 必须是不小于 0 的秒数");
        }
        if (from != null && to != null && to < from) {
            throw BadRequestException.invalidParameter("to 不能小于 from");
        }
    }

    private static LocalDateTime validateCreatedAt(LocalDateTime createdAt) {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt.isAfter(now.plus(CLOCK_SKEW))) {
            throw BadRequestException.invalidParameter("createdAt 不能是未来时间");
        }
        if (createdAt.isBefore(now.minus(ADOPT_CREATED_AT_RETENTION))) {
            throw BadRequestException.invalidParameter("createdAt 早于 30 天前，拒绝登记");
        }
        return createdAt;
    }

    private Double durationOf(PolyvSubtitleTaskEntity row) {
        if (row.getVideoDurationSec() != null) {
            return row.getVideoDurationSec().doubleValue();
        }
        Optional<Double> configured = props.durationOf(row.getVideo());
        if (configured.isPresent()) {
            return configured.get();
        }
        var material = materialService.findByRef(row.getVideo());
        return material == null || material.getDurationSec() == null
                ? null : material.getDurationSec().doubleValue();
    }

    private static boolean reusable(PolyvSubtitleTaskEntity row) {
        return row != null
                && row.getTaskId() != null
                && !row.getTaskId().isBlank()
                && row.getStatus() != null
                && row.getStatus().reusable();
    }

    /** 某个视频标识下已就绪的登记行（最新创建优先）。 */
    private PolyvSubtitleTaskEntity findReadyRow(String video) {
        return taskMapper.selectList(Wrappers.lambdaQuery(PolyvSubtitleTaskEntity.class)
                        .eq(PolyvSubtitleTaskEntity::getVideo, video)
                        .orderByDesc(PolyvSubtitleTaskEntity::getCreatedAt)
                        .orderByDesc(PolyvSubtitleTaskEntity::getId))
                .stream()
                .filter(SmartSubtitleService::isReady)
                .findFirst()
                .orElse(null);
    }

    /** 按 VID 找登记行：一个云端视频只应有一份字幕任务，本地素材可以共用。 */
    private PolyvSubtitleTaskEntity findByVid(String vid) {
        if (vid == null || vid.isBlank()) {
            return null;
        }
        return taskMapper.selectOne(Wrappers.lambdaQuery(PolyvSubtitleTaskEntity.class)
                .eq(PolyvSubtitleTaskEntity::getVid, vid)
                .orderByDesc(PolyvSubtitleTaskEntity::getCreatedAt)
                .orderByDesc(PolyvSubtitleTaskEntity::getId)
                .last("LIMIT 1"));
    }

    private static boolean isReady(PolyvSubtitleTaskEntity row) {
        return row != null
                && row.getStatus() == SmartSubtitleTaskStatus.READY
                && row.getTaskId() != null
                && !row.getTaskId().isBlank();
    }

    private PolyvSubtitleTaskEntity findByVideoAndVid(String video, String vid) {        return taskMapper.selectOne(Wrappers.lambdaQuery(PolyvSubtitleTaskEntity.class)
                .eq(PolyvSubtitleTaskEntity::getVideo, video)
                .eq(PolyvSubtitleTaskEntity::getVid, vid)
                .last("LIMIT 1"));
    }

    private PolyvSubtitleTaskEntity findByTaskId(String taskId) {
        return taskMapper.selectOne(Wrappers.lambdaQuery(PolyvSubtitleTaskEntity.class)
                .eq(PolyvSubtitleTaskEntity::getTaskId, taskId)
                .last("LIMIT 1"));
    }

    private PolyvSubtitleTaskEntity requireLocalTask(String taskId) {
        PolyvSubtitleTaskEntity row = findByTaskId(taskId);
        if (row == null) {
            throw PolyvErrors.localTaskNotFound(taskId);
        }
        return row;
    }

    private void applyReady(PolyvSubtitleTaskEntity row, int cueCount) {
        LocalDateTime now = LocalDateTime.now();
        row.setStatus(SmartSubtitleTaskStatus.READY);
        row.setSubtitleCount(cueCount);
        row.setLastCheckedAt(now);
        row.setLastSuccessAt(now);
        row.setErrorCode(null);
        row.setErrorMessage(null);
    }

    /** 把失败写进登记表（脱敏），再让调用方如实抛错。 */
    private void persistFailure(PolyvSubtitleTaskEntity row, ApiException error) {
        row.setStatus("POLYV_TASK_NOT_FOUND".equals(error.getCode())
                ? SmartSubtitleTaskStatus.NOT_FOUND
                : SmartSubtitleTaskStatus.FAILED);
        row.setLastCheckedAt(LocalDateTime.now());
        row.setErrorCode(error.getCode());
        row.setErrorMessage(sanitize(error.getMessage()));
        taskMapper.updateById(row);
        log.warn("智能字幕任务失败 taskId={} code={} message={}",
                row.getTaskId(), error.getCode(), row.getErrorMessage());
    }

    /**
     * 错误信息脱敏：去掉 sign / appSecret 参数与 32 位十六进制串，并截断。
     * 任何密钥、签名都不得进入数据库。
     */
    static String sanitize(String message) {
        if (message == null) {
            return null;
        }
        String cleaned = message
                .replaceAll("(?i)sign=[^&\\s]*", "sign=***")
                .replaceAll("(?i)(appsecret|secretkey)=[^&\\s]*", "$1=***")
                .replaceAll("\\b[0-9A-Fa-f]{32}\\b", "***")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.length() <= ERROR_MESSAGE_MAX ? cleaned : cleaned.substring(0, ERROR_MESSAGE_MAX) + "…";
    }

    private static BigDecimal toDecimal(Double seconds) {
        return seconds == null ? null : BigDecimal.valueOf(seconds).setScale(3, RoundingMode.HALF_UP);
    }

    static SmartSubtitleTaskDto toDto(PolyvSubtitleTaskEntity entity, Boolean reused) {
        return new SmartSubtitleTaskDto(
                entity.getId(),
                entity.getVideo(),
                entity.getVid(),
                entity.getTaskId(),
                entity.getLanguage(),
                entity.getStatus(),
                entity.getVideoDurationSec() == null ? null : entity.getVideoDurationSec().doubleValue(),
                entity.getSubtitleCount(),
                entity.getCreatedAt(),
                entity.getLastCheckedAt(),
                entity.getLastSuccessAt(),
                entity.getErrorCode(),
                entity.getErrorMessage(),
                reused);
    }
}
