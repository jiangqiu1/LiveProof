package com.liveproof.controller;

import com.liveproof.dto.CaseCandidateBatchRequest;
import com.liveproof.dto.CaseCandidateBatchResult;
import com.liveproof.dto.CaseCreateRequest;
import com.liveproof.dto.CaseDetail;
import com.liveproof.dto.CaseImportPreviewDto;
import com.liveproof.dto.CaseImportResultDto;
import com.liveproof.dto.CaseListItem;
import com.liveproof.dto.CasePatchRequest;
import com.liveproof.dto.DatasetDto;
import com.liveproof.enums.AnswerStatus;
import com.liveproof.enums.EnumSupport;
import com.liveproof.enums.EvidenceSufficiency;
import com.liveproof.enums.EvidenceType;
import com.liveproof.enums.ReviewStatus;
import com.liveproof.enums.ValueEnum;
import com.liveproof.exception.BadRequestException;
import com.liveproof.service.CaseCandidateService;
import com.liveproof.service.CaseImportService;
import com.liveproof.service.CaseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 核验用例接口。
 *
 * <p>过滤参数与改判字段全部按四字段独立处理：参数缺省表示"不过滤/不修改"。
 */
@RestController
@RequestMapping("/api")
public class CaseController {

    private final CaseService caseService;
    private final CaseImportService caseImportService;
    private final CaseCandidateService caseCandidateService;

    public CaseController(CaseService caseService, CaseImportService caseImportService,
                          CaseCandidateService caseCandidateService) {
        this.caseService = caseService;
        this.caseImportService = caseImportService;
        this.caseCandidateService = caseCandidateService;
    }

    @GetMapping("/datasets")
    public List<DatasetDto> datasets() {
        return caseService.datasets();
    }

    @GetMapping("/cases")
    public List<CaseListItem> cases(
            @RequestParam(name = "ds", required = false) String ds,
            @RequestParam(name = "materialId", required = false) String materialId,
            @RequestParam(name = "reviewStatus", required = false) String reviewStatus,
            @RequestParam(name = "answerStatus", required = false) String answerStatus,
            @RequestParam(name = "evidenceType", required = false) String evidenceType,
            @RequestParam(name = "evidenceSufficiency", required = false) String evidenceSufficiency) {

        return caseService.list(
                ds,
                materialId,
                queryParam(ReviewStatus.class, reviewStatus, "reviewStatus"),
                queryParam(AnswerStatus.class, answerStatus, "answerStatus"),
                queryParam(EvidenceType.class, evidenceType, "evidenceType"),
                queryParam(EvidenceSufficiency.class, evidenceSufficiency, "evidenceSufficiency"));
    }

    @PostMapping("/cases")
    public ResponseEntity<CaseDetail> create(@RequestBody CaseCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(caseService.create(request));
    }

    /**
     * 把用户勾选过的 AI 候选事项确认入库。
     *
     * <p>与 {@code POST /api/cases} 的区别只有两点：来源固定记 {@code ai}；
     * 一次提交多条且<b>要么全写入、要么整批拒绝</b>。
     * 核验字段的默认值完全一致——写进来的是「尚未核验」。
     */
    @PostMapping("/cases/from-candidates")
    public ResponseEntity<CaseCandidateBatchResult> createFromCandidates(
            @RequestBody CaseCandidateBatchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(caseCandidateService.createBatch(request));
    }

    // ------------------------------------------------------------------ CSV 导入

    /** 下载 CSV 模板（表头 + 一行格式示例）。 */
    @GetMapping("/cases/import/template")
    public ResponseEntity<byte[]> importTemplate() {
        byte[] body = caseImportService.template().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"cases-template.csv\"; filename*=UTF-8''"
                                + "%E6%A0%B8%E9%AA%8C%E4%BA%8B%E9%A1%B9%E6%A8%A1%E6%9D%BF.csv")
                .body(body);
    }

    /**
     * 导入预览：解析 + 逐行校验 + 重复检测，<b>不写库</b>。
     *
     * <p>前端必须先把预览结果展示给用户确认，再由用户点击「确认导入」。
     */
    @PostMapping(value = "/cases/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CaseImportPreviewDto importPreview(
            @RequestParam(name = "materialId", required = false) String materialId,
            @RequestPart(name = "file", required = false) MultipartFile file) {
        return caseImportService.preview(materialId, file);
    }

    /**
     * 执行导入。
     *
     * @param policy {@code strict}（默认，有非法行整批拒绝）或 {@code skip_invalid}
     */
    @PostMapping(value = "/cases/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CaseImportResultDto importCases(
            @RequestParam(name = "materialId", required = false) String materialId,
            @RequestPart(name = "file", required = false) MultipartFile file,
            @RequestParam(name = "policy", required = false) String policy) {
        return caseImportService.importCases(materialId, file, policy);
    }

    /** 默认拒绝有挂接待办的事项；{@code withTodos=true} 才连带删除。 */
    @DeleteMapping("/cases/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id,
                                       @RequestParam(name = "withTodos", defaultValue = "false") boolean withTodos) {
        caseService.delete(id, withTodos);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cases/{id}")
    public CaseDetail caseDetail(@PathVariable("id") String id) {
        return caseService.detail(id);
    }

    @PatchMapping("/cases/{id}")
    public CaseDetail patch(@PathVariable("id") String id,
                            @Valid @RequestBody CasePatchRequest request) {
        return caseService.patch(id, request);
    }

    /** 查询参数：缺省或空串视为未提供；非法取值转 400。 */
    private static <E extends Enum<E> & ValueEnum> E queryParam(Class<E> type, String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EnumSupport.parse(type, raw, field);
        } catch (IllegalArgumentException e) {
            throw BadRequestException.invalidParameter(e.getMessage());
        }
    }
}
