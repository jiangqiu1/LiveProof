package com.liveproof.controller;

import com.liveproof.dto.ReviewReportResponse;
import com.liveproof.exception.BadRequestException;
import com.liveproof.service.ReviewReportService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 复盘报告接口。
 *
 * <p>报告即时生成、不落库：统计与逐项事实来自数据库已保存记录，
 * AI 归纳（若已配置）在生成时单独调用并标注。
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private static final int MAX_DS_LENGTH = 32;

    private final ReviewReportService reportService;

    public ReportController(ReviewReportService reportService) {
        this.reportService = reportService;
    }

    /** 生成报告并返回 Markdown 正文（供预览与下载）。 */
    @PostMapping(value = "/review", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReviewReportResponse review(@RequestParam(name = "ds", required = false) String ds,
                                       @RequestParam(name = "materialId", required = false) String materialId) {
        return reportService.generate(validate(ds), validateMaterial(materialId));
    }

    /**
     * 直接下载 Markdown。
     *
     * <p>注意：该端点每次请求都会重新生成（配置了密钥时会再触发一次模型归纳），
     * 前端正常路径应使用上一步 POST 的返回内容做下载，避免重复消耗额度。
     */
    @GetMapping(value = "/review.md", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<byte[]> download(@RequestParam(name = "ds", required = false) String ds,
                                           @RequestParam(name = "materialId", required = false) String materialId) {
        String dataset = validate(ds);
        String material = validateMaterial(materialId);
        ReviewReportResponse report = reportService.generate(dataset, material);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(reportService.fileName(dataset, material), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .body(report.markdown().getBytes(StandardCharsets.UTF_8));
    }

    /** 素材 id 只允许 ASCII 标识符，避免被当成任意字符串拼进查询或文件名。 */
    private static String validateMaterial(String materialId) {
        if (materialId == null || materialId.isBlank()) {
            return null;
        }
        String value = materialId.trim();
        if (value.length() > 40) {
            throw BadRequestException.invalidParameter("materialId 长度超过 40 字符上限");
        }
        if (!value.matches("[A-Za-z0-9_-]+")) {
            throw BadRequestException.invalidParameter("materialId 只接受字母、数字、下划线与连字符");
        }
        return value;
    }

    private static String validate(String ds) {
        if (ds == null || ds.isBlank()) {
            return null;
        }
        String value = ds.trim();
        if (value.length() > MAX_DS_LENGTH) {
            throw BadRequestException.invalidParameter("ds 长度超过 " + MAX_DS_LENGTH + " 字符上限");
        }
        if (!value.matches("[A-Za-z0-9_-]+")) {
            throw BadRequestException.invalidParameter("ds 只接受字母、数字、下划线与连字符");
        }
        return value;
    }
}
