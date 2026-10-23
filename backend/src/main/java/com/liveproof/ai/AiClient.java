package com.liveproof.ai;

import java.util.List;

/**
 * 模型调用抽象。
 *
 * <p>抽成接口的目的：让业务层在测试中可以替换为桩实现，
 * 生产实现（{@link DeepSeekClient}）不在浏览器侧、不在前端产物里。
 *
 * <p>约定：实现类在未配置密钥时由 {@code isConfigured()} 返回 false，
 * 由业务层抛出 {@code AI_NOT_CONFIGURED}，<b>不得</b>返回伪造内容。
 */
public interface AiClient {

    boolean isConfigured();

    String model();

    /** 模型服务主机名（仅用于状态展示，不含密钥）。 */
    String endpointHost();

    /**
     * 发起一次对话补全。
     *
     * @param systemPrompt 系统约束
     * @param userPrompt   用户内容（事实材料）
     * @param frames       关键帧；空列表表示本次只提交文本
     * @param jsonMode     是否要求上游以 JSON 对象返回
     */
    AiCompletion complete(String systemPrompt, String userPrompt, List<FrameImage> frames, boolean jsonMode);
}
