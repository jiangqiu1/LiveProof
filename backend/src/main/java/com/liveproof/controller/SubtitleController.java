package com.liveproof.controller;

import com.liveproof.dto.SubtitleResponse;
import com.liveproof.service.SubtitleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 字幕接口。当前阶段只支持本地自制素材，不调用 POLYV 智能字幕。
 */
@RestController
public class SubtitleController {

    private final SubtitleService subtitleService;

    public SubtitleController(SubtitleService subtitleService) {
        this.subtitleService = subtitleService;
    }

    @GetMapping("/api/subtitles")
    public SubtitleResponse subtitles(@RequestParam("video") String video) {
        return subtitleService.forVideo(video);
    }
}
