package com.liveproof.ai;

/**
 * 一次模型调用的结果（只保留文本与用量，不保留请求体）。
 */
public record AiCompletion(
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens
) {
}
