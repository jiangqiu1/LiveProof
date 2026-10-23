package com.liveproof.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liveproof.dto.CaseCreateRequest;
import com.liveproof.dto.CaseDetail;
import com.liveproof.dto.CaseListItem;
import com.liveproof.dto.CasePatchRequest;
import com.liveproof.dto.DatasetDto;
import com.liveproof.entity.CaseEntity;
import com.liveproof.entity.MaterialEntity;
import com.liveproof.entity.TodoEntity;
import com.liveproof.enums.AnswerStatus;
import com.liveproof.enums.EvidenceSufficiency;
import com.liveproof.enums.EvidenceType;
import com.liveproof.enums.ReviewStatus;
import com.liveproof.exception.ApiException;
import com.liveproof.exception.BadRequestException;
import com.liveproof.exception.NotFoundException;
import com.liveproof.mapper.CaseMapper;
import com.liveproof.mapper.TodoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 核验用例查询与改判。
 *
 * <p>核心约束：改判时四个字段各自独立写入，只更新请求中出现的字段。
 * 本类<b>不存在</b>任何依据一个字段推导另一个字段的代码路径。
 */
@Service
public class CaseService {

    private static final Logger log = LoggerFactory.getLogger(CaseService.class);

    private static final Map<String, String> DATASET_LABELS = new LinkedHashMap<>();

    static {
        DATASET_LABELS.put("cf", "CandidateFlow 招聘管理系统（自制）");
        DATASET_LABELS.put("lc", "外部示例视频（素材不可公开演示）");
    }

    private final CaseMapper caseMapper;
    private final TodoMapper todoMapper;
    private final MaterialService materialService;
    private final ObjectMapper objectMapper;

    public CaseService(CaseMapper caseMapper,
                       TodoMapper todoMapper,
                       MaterialService materialService,
                       ObjectMapper objectMapper) {
        this.caseMapper = caseMapper;
        this.todoMapper = todoMapper;
        this.materialService = materialService;
        this.objectMapper = objectMapper;
    }

    /** 数据集列表，按 cf 优先排序，与旧实现行为一致。 */
    public List<DatasetDto> datasets() {
        QueryWrapper<CaseEntity> wrapper = new QueryWrapper<>();
        wrapper.select("ds", "COUNT(*) AS cnt").groupBy("ds");
        List<Map<String, Object>> rows = caseMapper.selectMaps(wrapper);

        return rows.stream()
                .map(row -> {
                    String ds = String.valueOf(row.get("ds"));
                    int count = ((Number) row.get("cnt")).intValue();
                    return new DatasetDto(ds, DATASET_LABELS.getOrDefault(ds, ds), count);
                })
                .sorted((a, b) -> {
                    boolean aCf = "cf".equals(a.id());
                    boolean bCf = "cf".equals(b.id());
                    if (aCf == bCf) {
                        return a.id().compareTo(b.id());
                    }
                    return aCf ? -1 : 1;
                })
                .toList();
    }

    /** 用例列表，支持按四字段过滤（全部可选，互不推导）；materialId 为可选的素材维度过滤。 */
    public List<CaseListItem> list(String ds,
                                   String materialId,
                                   ReviewStatus reviewStatus,
                                   AnswerStatus answerStatus,
                                   EvidenceType evidenceType,
                                   EvidenceSufficiency evidenceSufficiency) {
        LambdaQueryWrapper<CaseEntity> wrapper = Wrappers.lambdaQuery(CaseEntity.class);
        if (ds != null && !ds.isBlank()) {
            wrapper.eq(CaseEntity::getDs, ds.trim());
        }
        if (materialId != null && !materialId.isBlank()) {
            wrapper.eq(CaseEntity::getMaterialId, materialId.trim());
        }
        if (reviewStatus != null) {
            wrapper.eq(CaseEntity::getReviewStatus, reviewStatus);
        }
        if (answerStatus != null) {
            wrapper.eq(CaseEntity::getAnswerStatus, answerStatus);
        }
        if (evidenceType != null) {
            wrapper.eq(CaseEntity::getEvidenceType, evidenceType);
        }
        if (evidenceSufficiency != null) {
            wrapper.eq(CaseEntity::getEvidenceSufficiency, evidenceSufficiency);
        }
        wrapper.orderByAsc(CaseEntity::getStartSec).orderByAsc(CaseEntity::getId);

        return caseMapper.selectList(wrapper).stream().map(CaseService::toListItem).toList();
    }

    public CaseDetail detail(String id) {
        CaseEntity entity = caseMapper.selectById(id);
        if (entity == null) {
            throw NotFoundException.caseNotFound(id);
        }
        return toDetail(entity);
    }

    /**
     * 手动新增核验事项。
     *
     * <p>{@code reviewStatus} 强制 {@code unreviewed}、{@code answerStatus} 落库默认值
     * {@code not_found}（占位值）、{@code evidenceType} 不预设：客户端无法直接把一条事项标成已核验。
     */
    public CaseDetail create(CaseCreateRequest request) {
        return create(request, "manual");
    }

    /**
     * 手动新增核验事项（可指定来源标记）。
     *
     * @param source 只记来源（manual / csv），<b>不影响</b>任何核验字段的默认值
     */
    public CaseDetail create(CaseCreateRequest request, String source) {
        if (request == null) {
            throw BadRequestException.invalidParameter("请求体不能为空");
        }
        String materialId = request.materialId() == null ? null : request.materialId().trim();
        if (materialId == null || materialId.isEmpty()) {
            throw BadRequestException.invalidParameter("materialId 不能为空：核验事项必须归属于某个素材");
        }
        MaterialEntity material = materialService.require(materialId);
        if (!material.isReady()) {
            throw new ApiException(HttpStatus.CONFLICT, "MATERIAL_NOT_READY",
                    "素材尚未就绪（当前状态：" + material.getStatus() + "），请等待转码完成后再新增核验事项");
        }

        String question = requireTitle(request.question(), "question");
        String claim = requireTitle(request.claim(), "claim");
        BigDecimal start = normalizeSec(request.startSec(), "startSec");
        BigDecimal end = normalizeSec(request.endSec(), "endSec");
        if ((start == null) != (end == null)) {
            throw BadRequestException.invalidParameter("startSec 与 endSec 必须成对出现或都不填");
        }
        if (start != null && start.compareTo(end) > 0) {
            throw BadRequestException.invalidParameter("startSec 不能晚于 endSec");
        }
        String reasoning = optionalText(request.reasoning(), "reasoning");
        String limitations = optionalText(request.limitations(), "limitations");
        String timePoints = request.timePoints() == null ? null : writeTimePoints(request.timePoints());

        CaseEntity entity = new CaseEntity();
        entity.setDs("cf");
        entity.setQuestion(question);
        entity.setClaim(claim);
        // 引用：内置素材用文件名（走既有 /media 与静态字幕映射），云端素材用素材 id
        entity.setVideo(material.getFileName() != null ? material.getFileName() : material.getId());
        entity.setMaterialId(material.getId());
        entity.setSource(source == null || source.isBlank() ? "manual" : source);
        entity.setStartSec(start);
        entity.setEndSec(end);
        entity.setReviewStatus(ReviewStatus.UNREVIEWED);
        entity.setAnswerStatus(AnswerStatus.NOT_FOUND);
        // 四字段必须全部落值：evidence_type 列允许 NULL，漏设会让新行走进「未定义」分支，
        // 前端无法把 null 渲染成「待确认」，报告归类也会漏掉这一行。固定为 unknown，需人工改判。
        entity.setEvidenceType(EvidenceType.UNKNOWN);
        entity.setEvidenceSufficiency(EvidenceSufficiency.NOT_CHECKED);
        entity.setReasoning(reasoning);
        entity.setLimitations(limitations);
        entity.setTimePoints(timePoints);
        entity.setFollowUpRequired(false);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        for (int attempt = 0; attempt < 3; attempt++) {
            entity.setId(nextCaseId());
            try {
                caseMapper.insert(entity);
                log.info("新增核验事项：id={} 素材={} 来源={}", entity.getId(), material.getId(), entity.getSource());
                return detail(entity.getId());
            } catch (org.springframework.dao.DuplicateKeyException e) {
                log.warn("核验事项 id 冲突，重试第 {} 次", attempt + 1);
            }
        }
        throw new IllegalStateException("生成核验事项 id 连续冲突，请重试");
    }

    /**
     * 删除核验事项。
     *
     * <p>默认拒绝有挂接待办的事项（红线：待办不能被静默丢弃）；{@code withTodos=true}
     * 才在同一事务内连同待办一起删除——这必须是一次显式选择。
     */
    public void delete(String id, boolean withTodos) {
        CaseEntity existing = caseMapper.selectById(id);
        if (existing == null) {
            throw NotFoundException.caseNotFound(id);
        }
        Long todos = todoMapper.selectCount(Wrappers.lambdaQuery(TodoEntity.class)
                .eq(TodoEntity::getCaseId, id));
        int count = todos == null ? 0 : todos.intValue();
        if (count > 0) {
            if (!withTodos) {
                throw new ApiException(HttpStatus.CONFLICT, "CASE_HAS_TODOS",
                        "该核验事项下仍有 " + count + " 条待办，删除会一并丢弃；"
                                + "确认要删除请带 withTodos=true");
            }
            todoMapper.delete(Wrappers.lambdaQuery(TodoEntity.class).eq(TodoEntity::getCaseId, id));
        }
        caseMapper.deleteById(id);
        log.info("删除核验事项：id={} 连带待办={}", id, withTodos ? count : 0);
    }

    /** 服务端生成 id：c + base36 毫秒 + 4 位随机，远小于 VARCHAR(64)。 */
    private static String nextCaseId() {
        return "c" + Long.toString(System.currentTimeMillis(), 36)
                + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0x10000));
    }

    private static String requireTitle(String raw, String field) {
        if (raw == null || raw.trim().isEmpty()) {
            throw BadRequestException.invalidParameter(field + " 不能为空");
        }
        String text = raw.trim();
        if (text.length() > CaseCreateRequest.MAX_TITLE_LENGTH) {
            throw BadRequestException.invalidParameter(
                    field + " 超过 " + CaseCreateRequest.MAX_TITLE_LENGTH + " 字符上限");
        }
        return text;
    }

    private static String optionalText(String raw, String field) {
        if (raw == null) {
            return null;
        }
        if (raw.length() > CasePatchRequest.MAX_TEXT_LENGTH) {
            throw BadRequestException.invalidParameter(
                    field + " 超过 " + CasePatchRequest.MAX_TEXT_LENGTH + " 字符上限");
        }
        return raw;
    }

    /** 秒：0–7200，统一两位小数（与 timePoints 同口径）。 */
    private static BigDecimal normalizeSec(BigDecimal raw, String field) {
        if (raw == null) {
            return null;
        }
        if (raw.signum() < 0 || raw.compareTo(CaseCreateRequest.MAX_SEC) > 0) {
            throw BadRequestException.invalidParameter(field + " 超出 0~7200 秒范围");
        }
        return raw.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 人工改判。先查存在性再更新（修正旧实现用 affectedRows 判 404 的缺陷 D3）。
     */
    public CaseDetail patch(String id, CasePatchRequest request) {
        if (request == null || request.isEmpty()) {
            throw BadRequestException.emptyPatch();
        }
        CaseEntity existing = caseMapper.selectById(id);
        if (existing == null) {
            throw NotFoundException.caseNotFound(id);
        }

        LambdaUpdateWrapper<CaseEntity> update = Wrappers.lambdaUpdate(CaseEntity.class)
                .eq(CaseEntity::getId, id);
        if (request.reviewStatus() != null) {
            update.set(CaseEntity::getReviewStatus, request.reviewStatus());
        }
        if (request.answerStatus() != null) {
            update.set(CaseEntity::getAnswerStatus, request.answerStatus());
        }
        if (request.evidenceType() != null) {
            update.set(CaseEntity::getEvidenceType, request.evidenceType());
        }
        if (request.evidenceSufficiency() != null) {
            update.set(CaseEntity::getEvidenceSufficiency, request.evidenceSufficiency());
        }
        if (request.reasoning() != null) {
            update.set(CaseEntity::getReasoning, checkText(request.reasoning(), "reasoning"));
        }
        if (request.limitations() != null) {
            update.set(CaseEntity::getLimitations, checkText(request.limitations(), "limitations"));
        }
        if (request.timePoints() != null) {
            update.set(CaseEntity::getTimePoints, writeTimePoints(request.timePoints()));
        }
        caseMapper.update(null, update);

        return detail(id);
    }

    /** 自由文本字段的长度校验。空串是合法值（表示清空），超长拒绝而不是静默截断。 */
    private static String checkText(String value, String field) {
        if (value.length() > CasePatchRequest.MAX_TEXT_LENGTH) {
            throw BadRequestException.invalidParameter(
                    field + " 超过 " + CasePatchRequest.MAX_TEXT_LENGTH + " 字符上限");
        }
        return value;
    }

    /**
     * 关联时间点校验与序列化。
     *
     * <p>规则：不接受 null 元素；0 ≤ sec ≤ 7200；统一保留两位小数；升序去重；最多 20 个。
     * 非法值直接 400，而不是静默丢弃——丢一个时间点等于改动核验结论。
     *
     * <p>空数组序列化为 {@code []} 而不是 NULL：库里区分不了两者也无所谓，
     * 但写出确定值比写 NULL 更容易在日志里看出"这次确实清空了"。
     */
    private String writeTimePoints(List<BigDecimal> raw) {
        if (raw.size() > CasePatchRequest.MAX_TIME_POINTS) {
            throw BadRequestException.invalidParameter(
                    "timePoints 超过 " + CasePatchRequest.MAX_TIME_POINTS + " 个上限");
        }
        TreeSet<BigDecimal> normalized = new TreeSet<>();
        for (BigDecimal value : raw) {
            if (value == null) {
                throw BadRequestException.invalidParameter("timePoints 不能包含 null");
            }
            if (value.signum() < 0 || value.compareTo(CasePatchRequest.MAX_TIME_POINT_SEC) > 0) {
                throw BadRequestException.invalidParameter(
                        "timePoints 超出 0~7200 秒范围：" + value.toPlainString());
            }
            normalized.add(value.setScale(2, RoundingMode.HALF_UP));
        }
        try {
            return objectMapper.writeValueAsString(new ArrayList<>(normalized));
        } catch (Exception e) {
            // 纯数值数组序列化不会失败；真失败说明 ObjectMapper 配置被改坏，宁可 500 也不静默吞掉
            throw new IllegalStateException("timePoints 序列化失败", e);
        }
    }

    /** time_points 是 JSON 数值数组；解析失败不伪造数据，仅告警并返回空列表。 */
    private List<BigDecimal> parseTimePoints(String caseId, String timePointsJson) {
        if (timePointsJson == null || timePointsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(timePointsJson, new TypeReference<List<BigDecimal>>() {
            });
        } catch (Exception e) {
            log.warn("用例 {} 的 time_points 不是数值数组，返回空列表：{}", caseId, e.getMessage());
            return List.of();
        }
    }

    private static CaseListItem toListItem(CaseEntity e) {
        return new CaseListItem(
                e.getId(), e.getDs(), e.getQuestion(), e.getClaim(), e.getVideo(),
                e.getMaterialId(), e.getSource(),
                e.getStartSec(), e.getEndSec(),
                e.getReviewStatus(), e.getAnswerStatus(),
                e.getEvidenceType(), e.getEvidenceSufficiency(),
                Boolean.TRUE.equals(e.getFollowUpRequired()),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    private CaseDetail toDetail(CaseEntity e) {
        return new CaseDetail(
                e.getId(), e.getDs(), e.getQuestion(), e.getClaim(), e.getVideo(),
                e.getMaterialId(), e.getSource(),
                e.getStartSec(), e.getEndSec(),
                e.getReviewStatus(), e.getAnswerStatus(),
                e.getEvidenceType(), e.getEvidenceSufficiency(),
                e.getEvidenceStatus(),
                e.getReasoning(), e.getLimitations(),
                parseTimePoints(e.getId(), e.getTimePoints()),
                parseFrames(e.getId(), e.getFramesJson()),
                Boolean.TRUE.equals(e.getFollowUpRequired()),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    /** frames_json 是 JSON 数组；解析失败不伪造数据，仅记录告警并返回空列表。 */
    private List<String> parseFrames(String caseId, String framesJson) {
        if (framesJson == null || framesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(framesJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("用例 {} 的 frames_json 不是字符串数组，返回空列表：{}", caseId, e.getMessage());
            return List.of();
        }
    }
}
