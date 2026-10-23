package com.liveproof.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liveproof.dto.CaseCreateRequest;
import com.liveproof.dto.CaseImportPreviewDto;
import com.liveproof.dto.CaseImportResultDto;
import com.liveproof.dto.CaseImportRow;
import com.liveproof.dto.CaseListItem;
import com.liveproof.entity.CaseEntity;
import com.liveproof.entity.MaterialEntity;
import com.liveproof.exception.ApiException;
import com.liveproof.exception.BadRequestException;
import com.liveproof.mapper.CaseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CSV 批量导入核验事项。
 *
 * <p>红线（与手动新增完全一致）：
 * <ul>
 *   <li>CSV 里<b>不接受</b>核验结论类列（reviewStatus / answerStatus / evidenceType /
 *       evidenceSufficiency）。出现这些列直接整批拒收，避免「导入即已核验」。</li>
 *   <li>导入产生的行一律 {@code reviewStatus=unreviewed}、{@code answerStatus=not_found}（占位）、
 *       {@code evidenceSufficiency=not_checked}、{@code source=csv}。</li>
 *   <li>预览<b>不写库</b>；只有显式执行导入才落库。</li>
 *   <li>CSV 导入是<b>人工整理的清单</b>，不是 AI 自动生成，接口返回里也不含任何模型调用。</li>
 * </ul>
 *
 * <p>编码：按 UTF-8 读取并去掉 BOM。Windows 下 Excel 另存为「CSV UTF-8」即可；
 * 若出现乱码，错误信息会提示改用 CSV UTF-8。
 */
@Service
public class CaseImportService {

    private static final Logger log = LoggerFactory.getLogger(CaseImportService.class);

    /** 最多接受的数据行数，防止一次导入把库塞满。 */
    static final int MAX_ROWS = 500;

    static final long MAX_BYTES = 2L * 1024 * 1024;

    /** 列名别名：同一语义接受中英文多种写法，全部小写去空格后比较。 */
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        alias("question", "question", "问题", "待核验问题", "核验问题", "疑问");
        alias("claim", "claim", "承诺", "产品承诺", "说法", "承诺描述");
        alias("startSec", "startsec", "start", "开始", "开始秒", "开始时间", "起点", "起始时间");
        alias("endSec", "endsec", "end", "结束", "结束秒", "结束时间", "终点", "终止时间");
    }

    /** 结论类列：出现即整批拒收。 */
    private static final Map<String, String> RESERVED = new LinkedHashMap<>();

    static {
        RESERVED.put("reviewstatus", "reviewStatus");
        RESERVED.put("review_status", "reviewStatus");
        RESERVED.put("核验状态", "reviewStatus");
        RESERVED.put("answerstatus", "answerStatus");
        RESERVED.put("answer_status", "answerStatus");
        RESERVED.put("回答状态", "answerStatus");
        RESERVED.put("evidencetype", "evidenceType");
        RESERVED.put("evidence_type", "evidenceType");
        RESERVED.put("证据形态", "evidenceType");
        RESERVED.put("evidencesufficiency", "evidenceSufficiency");
        RESERVED.put("evidence_sufficiency", "evidenceSufficiency");
        RESERVED.put("证据充分性", "evidenceSufficiency");
    }

    private static void alias(String field, String... names) {
        for (String name : names) {
            ALIASES.put(name, field);
        }
    }

    private final CaseMapper caseMapper;
    private final MaterialService materialService;
    private final CaseService caseService;

    public CaseImportService(CaseMapper caseMapper, MaterialService materialService, CaseService caseService) {
        this.caseMapper = caseMapper;
        this.materialService = materialService;
        this.caseService = caseService;
    }

    // ------------------------------------------------------------------ 模板

    /** CSV 模板：表头 + 一行示例。示例行是<b>格式演示</b>，需用户自行改写。 */
    public String template() {
        return "问题,承诺,开始秒,结束秒\n"
                + "房间容量上限能改到 5000 吗,支持将房间容量上限调整到 5000,40,60\n";
    }

    // ------------------------------------------------------------------ 预览

    /** 解析并校验，不写库。 */
    public CaseImportPreviewDto preview(String materialId, MultipartFile file) {
        MaterialEntity material = requireReadyMaterial(materialId);
        Parsed parsed = parse(file);
        List<CaseImportRow> rows = validateRows(material, parsed);
        int valid = (int) rows.stream().filter(CaseImportRow::valid).count();
        int duplicates = (int) rows.stream()
                .filter(row -> row.problems().stream().anyMatch(p -> p.contains("重复")))
                .count();
        return new CaseImportPreviewDto(material.getId(), rows.size(), valid, rows.size() - valid,
                duplicates, rows, parsed.headers(), parsed.unmapped());
    }

    // ------------------------------------------------------------------ 导入

    /**
     * 执行导入。
     *
     * @param policy {@code strict}（默认，有非法行则整批拒绝）或 {@code skip_invalid}
     */
    @Transactional
    public CaseImportResultDto importCases(String materialId, MultipartFile file, String policy) {
        MaterialEntity material = requireReadyMaterial(materialId);
        String effective = normalizePolicy(policy);
        Parsed parsed = parse(file);
        List<CaseImportRow> rows = validateRows(material, parsed);

        List<CaseImportRow> bad = rows.stream().filter(row -> !row.valid()).toList();
        if ("strict".equals(effective) && !bad.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CASE_IMPORT_HAS_INVALID_ROWS",
                    "有 " + bad.size() + " 行未通过校验，整批未导入。"
                            + "可改用 policy=skip_invalid 跳过这些行，或修正后重试");
        }

        List<CaseListItem> created = new ArrayList<>();
        for (CaseImportRow row : rows) {
            if (!row.valid()) {
                continue;
            }
            var detail = caseService.create(new CaseCreateRequest(
                    material.getId(), row.question(), row.claim(), row.startSec(), row.endSec(),
                    null, null, null), "csv");
            created.add(new CaseListItem(
                    detail.id(), detail.ds(), detail.question(), detail.claim(), detail.video(),
                    detail.materialId(), detail.source(),
                    detail.startSec(), detail.endSec(),
                    detail.reviewStatus(), detail.answerStatus(),
                    detail.evidenceType(), detail.evidenceSufficiency(),
                    detail.followUpRequired(), detail.createdAt(), detail.updatedAt()));
        }

        List<CaseImportResultDto.SkippedRow> skipped = bad.stream()
                .map(row -> new CaseImportResultDto.SkippedRow(row.line(), row.problems()))
                .toList();
        log.info("CSV 导入完成：素材={} 策略={} 成功={} 跳过={}",
                material.getId(), effective, created.size(), skipped.size());
        return new CaseImportResultDto(material.getId(), created.size(), skipped.size(), skipped,
                created, effective);
    }

    // ------------------------------------------------------------------ 解析

    /** 解析结果：表头、未识别列、数据行。 */
    private record Parsed(List<String> headers, List<String> unmapped, List<RawRow> rows) {
    }

    /** 原始行：行号 + 按字段归位的文本值。 */
    private record RawRow(int line, Map<String, String> values) {
    }

    private Parsed parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BadRequestException.invalidParameter("请选择要导入的 CSV 文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw BadRequestException.invalidParameter("CSV 文件超过 2 MB 上限");
        }
        String text;
        try {
            text = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw BadRequestException.invalidParameter("读取 CSV 文件失败");
        }
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        if (text.contains("\uFFFD")) {
            throw BadRequestException.invalidParameter(
                    "CSV 含有无法解码的字符，请用 Excel「另存为 CSV UTF-8」后重试");
        }
        List<List<String>> table = parseCsv(text);
        if (table.isEmpty()) {
            throw BadRequestException.invalidParameter("CSV 内容为空");
        }

        List<String> headers = table.get(0);
        Map<Integer, String> columnMap = new LinkedHashMap<>();
        List<String> unmapped = new ArrayList<>();
        Set<String> reservedHits = new LinkedHashSet<>();
        for (int i = 0; i < headers.size(); i++) {
            String raw = headers.get(i) == null ? "" : headers.get(i).trim();
            String key = normalizeHeader(raw);
            if (key.isEmpty()) {
                continue;
            }
            if (RESERVED.containsKey(key)) {
                reservedHits.add(raw.isEmpty() ? RESERVED.get(key) : raw);
                continue;
            }
            String field = ALIASES.get(key);
            if (field == null) {
                unmapped.add(raw);
            } else if (!columnMap.containsValue(field)) {
                columnMap.put(i, field);
            }
        }
        if (!reservedHits.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CASE_IMPORT_RESERVED_COLUMN",
                    "CSV 含核验结论列 " + reservedHits + "，不允许通过导入设置核验结论；"
                            + "请删除这些列后重试");
        }
        if (!columnMap.containsValue("question") || !columnMap.containsValue("claim")) {
            throw BadRequestException.invalidParameter(
                    "CSV 表头必须包含「问题」与「承诺」两列（也接受 question / claim）");
        }

        List<RawRow> rows = new ArrayList<>();
        for (int r = 1; r < table.size(); r++) {
            List<String> cells = table.get(r);
            if (cells.stream().allMatch(cell -> cell == null || cell.isBlank())) {
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            columnMap.forEach((index, field) ->
                    values.put(field, index < cells.size() ? cells.get(index) : null));
            rows.add(new RawRow(r + 1, values));
            if (rows.size() > MAX_ROWS) {
                throw BadRequestException.invalidParameter("CSV 数据行超过 " + MAX_ROWS + " 行上限");
            }
        }
        if (rows.isEmpty()) {
            throw BadRequestException.invalidParameter("CSV 只有表头，没有数据行");
        }
        return new Parsed(headers, unmapped, rows);
    }

    /** 列名归一：去空格、去全角空格、转小写、去掉常见包装符号。 */
    private static String normalizeHeader(String raw) {
        return raw == null ? "" : raw.replaceAll("[\\s\\u00A0_\\-]", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 极简 CSV 解析：支持双引号包裹、{{""}} 转义、字段内换行。
     * 不引第三方依赖——格式简单且必须可预测。
     */
    static List<List<String>> parseCsv(String text) {
        List<List<String>> table = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
                continue;
            }
            if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                table.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            table.add(row);
        }
        return table;
    }

    // ------------------------------------------------------------------ 校验

    private List<CaseImportRow> validateRows(MaterialEntity material, Parsed parsed) {
        Set<String> existing = existingKeys(material.getId());
        Set<String> seen = new HashSet<>();
        List<CaseImportRow> rows = new ArrayList<>();
        for (RawRow raw : parsed.rows()) {
            List<String> problems = new ArrayList<>();
            String question = trimToNull(raw.values().get("question"));
            String claim = trimToNull(raw.values().get("claim"));
            BigDecimal start = parseSec(raw.values().get("startSec"), "开始秒", problems);
            BigDecimal end = parseSec(raw.values().get("endSec"), "结束秒", problems);

            if (question == null) {
                problems.add("问题不能为空");
            } else if (question.length() > CaseCreateRequest.MAX_TITLE_LENGTH) {
                problems.add("问题超过 " + CaseCreateRequest.MAX_TITLE_LENGTH + " 字符");
            }
            if (claim == null) {
                problems.add("承诺不能为空");
            } else if (claim.length() > CaseCreateRequest.MAX_TITLE_LENGTH) {
                problems.add("承诺超过 " + CaseCreateRequest.MAX_TITLE_LENGTH + " 字符");
            }
            if ((start == null) != (end == null)) {
                problems.add("开始秒与结束秒必须成对填写或都留空");
            }
            if (start != null && end != null && start.compareTo(end) > 0) {
                problems.add("开始秒不能大于结束秒");
            }
            if (question != null) {
                String key = key(question);
                if (existing.contains(key)) {
                    problems.add("与库内已有事项重复（同一问题 + 同一素材）");
                } else if (!seen.add(key)) {
                    problems.add("与本文件前面的行重复");
                }
            }
            rows.add(new CaseImportRow(raw.line(), question, claim, start, end, problems));
        }
        return rows;
    }

    /** 库内已存在事项的归一键（问题文本 + 素材），用于重复检测。 */
    private Set<String> existingKeys(String materialId) {
        List<CaseEntity> list = caseMapper.selectList(new LambdaQueryWrapper<CaseEntity>()
                .eq(CaseEntity::getMaterialId, materialId));
        Set<String> keys = new HashSet<>();
        for (CaseEntity entity : list) {
            if (entity.getQuestion() != null) {
                keys.add(key(entity.getQuestion()));
            }
        }
        return keys;
    }

    /** 归一：去全部空白与常见标点后小写，避免「能改到 5000」与「能改到5000。」被判为不同。 */
    static String key(String question) {
        return question.replaceAll("[\\s\\p{Punct}，。：；！？、（）“”‘’《》]", "").toLowerCase(Locale.ROOT);
    }

    private static BigDecimal parseSec(String raw, String field, List<String> problems) {
        String text = trimToNull(raw);
        if (text == null) {
            return null;
        }
        // 允许写成 mm:ss 或 hh:mm:ss，也允许纯秒数
        try {
            double seconds;
            if (text.contains(":")) {
                String[] parts = text.split(":");
                if (parts.length == 2) {
                    seconds = Integer.parseInt(parts[0].trim()) * 60 + Double.parseDouble(parts[1].trim());
                } else if (parts.length == 3) {
                    seconds = Integer.parseInt(parts[0].trim()) * 3600
                            + Integer.parseInt(parts[1].trim()) * 60
                            + Double.parseDouble(parts[2].trim());
                } else {
                    problems.add(field + " 格式无法识别：" + text);
                    return null;
                }
            } else {
                seconds = Double.parseDouble(text.replace(',', '.'));
            }
            if (seconds < 0 || seconds > CaseCreateRequest.MAX_SEC.doubleValue()) {
                problems.add(field + " 超出 0~7200 秒范围：" + text);
                return null;
            }
            return BigDecimal.valueOf(seconds).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            problems.add(field + " 不是数字：" + text);
            return null;
        }
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.replace("\u00A0", " ").trim();
        return text.isEmpty() ? null : text;
    }

    private static String normalizePolicy(String policy) {
        String value = policy == null || policy.isBlank() ? "strict" : policy.trim().toLowerCase(Locale.ROOT);
        if (!"strict".equals(value) && !"skip_invalid".equals(value)) {
            throw BadRequestException.invalidParameter("policy 只接受 strict 或 skip_invalid");
        }
        return value;
    }

    private MaterialEntity requireReadyMaterial(String materialId) {
        String id = materialId == null ? "" : materialId.trim();
        if (id.isEmpty()) {
            throw BadRequestException.invalidParameter("materialId 不能为空：导入必须指定归属素材");
        }
        MaterialEntity material = materialService.require(id);
        if (!material.isReady()) {
            throw new ApiException(HttpStatus.CONFLICT, "MATERIAL_NOT_READY",
                    "素材尚未就绪（当前状态：" + material.getStatus() + "），请等待转码完成后再导入");
        }
        return material;
    }
}