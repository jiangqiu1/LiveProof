package com.liveproof.dto;

/**
 * AI 能力状态（只读，不触发任何模型调用）。
 *
 * @param configured       运行时是否配置了 API Key
 * @param endpointHost     模型服务主机名（不含密钥、不含路径参数）
 * @param ffmpegAvailable  FFmpeg 是否可用（决定本地素材能否做画面核验）
 * @param visionEnabled    配置层面是否允许提交关键帧
 * @param polyvUpload      保利威点播上传能力是否就绪（决定能否新建云端素材）
 * @param polyvSnapshot    保利威截图能力是否就绪（决定云端素材能否做画面核验）
 */
public record AiStatusDto(
        boolean configured,
        String model,
        String endpointHost,
        boolean visionEnabled,
        boolean ffmpegAvailable,
        boolean polyvUpload,
        boolean polyvSnapshot,
        int maxFrames,
        String note
) {
}
