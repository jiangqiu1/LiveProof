package com.liveproof.controller;

import com.liveproof.config.MaterialProperties;
import com.liveproof.config.PolyvProperties;
import com.liveproof.dto.MaterialDto;
import com.liveproof.dto.MaterialPlayDto;
import com.liveproof.service.MaterialService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 素材库接口。
 *
 * <p>上传是「同步转发保利威 + 异步转码」：接口返回 202 + {@code status=transcoding}，
 * 前端轮询 {@code GET /api/materials/{id}} 直到 {@code ready}。
 *
 * <p>安全：响应只含素材 id 与展示用文件名，不含绝对路径；上传不接收客户端指定的 vid。
 */
@RestController
@RequestMapping("/api/materials")
public class MaterialController {

    private final MaterialService materialService;
    private final PolyvProperties polyvProperties;
    private final MaterialProperties materialProperties;

    public MaterialController(MaterialService materialService,
                              PolyvProperties polyvProperties,
                              MaterialProperties materialProperties) {
        this.materialService = materialService;
        this.polyvProperties = polyvProperties;
        this.materialProperties = materialProperties;
    }

    @GetMapping
    public List<MaterialDto> list() {
        return materialService.list();
    }

    /** 前端据此决定是否禁用上传按钮，而不是让用户点了才失败。 */
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return Map.of(
                "uploadEnabled", polyvProperties.uploadConfigured(),
                "vodConfigured", polyvProperties.vodConfigured(),
                "maxBytes", materialProperties.getMaxBytes(),
                "maxCount", materialProperties.getMaxCount());
    }

    @GetMapping("/{id}")
    public MaterialDto get(@PathVariable("id") String id) {
        return materialService.get(id);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MaterialDto> upload(@RequestParam("file") MultipartFile file) {
        MaterialDto created = materialService.upload(file);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(created);
    }

    @GetMapping("/{id}/play")
    public MaterialPlayDto play(@PathVariable("id") String id) {
        return materialService.play(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        materialService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{id}/subtitle", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MaterialDto uploadSubtitle(@PathVariable("id") String id,
                                      @RequestParam("file") MultipartFile file) {
        return materialService.uploadSrt(id, file);
    }

    @DeleteMapping("/{id}/subtitle")
    public MaterialDto deleteSubtitle(@PathVariable("id") String id) {
        return materialService.deleteSrt(id);
    }
}
