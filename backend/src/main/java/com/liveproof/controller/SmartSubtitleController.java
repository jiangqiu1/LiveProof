package com.liveproof.controller;

import com.liveproof.dto.SmartSubtitleAdoptRequest;
import com.liveproof.dto.SmartSubtitleCreateRequest;
import com.liveproof.dto.SmartSubtitleCuesDto;
import com.liveproof.dto.SmartSubtitleStatusDto;
import com.liveproof.dto.SmartSubtitleTaskDto;
import com.liveproof.service.SmartSubtitleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 保利威智能字幕接口。
 *
 * <p>与既有的 {@code GET /api/subtitles?video=...}（本地 SRT）完全独立：
 * 本组接口读取的是<b>云端</b>智能字幕，行为、错误码、响应结构都不同，
 * 保留本地接口不动，避免改变既有前端与测试的语义。
 *
 * <p>URL 里只出现 taskId，不出现 VID 与凭据；VID 由服务端按视频标识查配置。
 */
@RestController
@RequestMapping("/api/smart-subtitles")
public class SmartSubtitleController {

    private final SmartSubtitleService service;

    public SmartSubtitleController(SmartSubtitleService service) {
        this.service = service;
    }

    /** 接入状态（只读，不触发云端调用）。 */
    @GetMapping("/status")
    public SmartSubtitleStatusDto status() {
        return service.status();
    }

    /** 本地任务登记列表（不查云端）。 */
    @GetMapping("/tasks")
    public List<SmartSubtitleTaskDto> tasks(@RequestParam(name = "video", required = false) String video) {
        return service.list(video);
    }

    /**
     * 创建任务或复用既有任务。
     *
     * @return 新建 201；复用既有任务 200（body.reused=true，未消耗资源点）
     */
    @PostMapping("/tasks")
    public ResponseEntity<SmartSubtitleTaskDto> create(@Valid @RequestBody SmartSubtitleCreateRequest request) {
        SmartSubtitleTaskDto task = service.createOrReuse(request);
        boolean reused = Boolean.TRUE.equals(task.reused());
        return ResponseEntity.status(reused ? HttpStatus.OK : HttpStatus.CREATED).body(task);
    }

    /** 登记既有任务（例如阶段一已创建的真实 taskId），不新建任务。 */
    @PostMapping("/tasks/adopt")
    public ResponseEntity<SmartSubtitleTaskDto> adopt(@Valid @RequestBody SmartSubtitleAdoptRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.adopt(request));
    }

    /** 查询任务状态（真实复查云端一次）。 */
    @GetMapping("/tasks/{taskId}")
    public SmartSubtitleTaskDto refresh(@PathVariable("taskId") String taskId) {
        return service.refresh(taskId);
    }

    /**
     * 取回规范化字幕（毫秒已转秒）。
     *
     * @param from 可选时间窗起点（秒）
     * @param to   可选时间窗终点（秒）
     */
    @GetMapping("/tasks/{taskId}/cues")
    public SmartSubtitleCuesDto cues(@PathVariable("taskId") String taskId,
                                     @RequestParam(name = "from", required = false) Double from,
                                     @RequestParam(name = "to", required = false) Double to) {
        return service.cues(taskId, from, to);
    }
}
