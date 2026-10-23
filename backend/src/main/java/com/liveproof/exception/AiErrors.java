package com.liveproof.exception;

import org.springframework.http.HttpStatus;

/**
 * AI 相关错误的统一构造入口。
 *
 * <p>错误码是前端分支依据，<b>不要</b>让前端匹配 message 文本：
 * <ul>
 *   <li>{@code AI_NOT_CONFIGURED} 503 —— 运行时未配置 API Key，属于部署状态而非故障</li>
 *   <li>{@code AI_AUTH_FAILED} 502 —— 密钥无效或无权限</li>
 *   <li>{@code AI_RATE_LIMITED} 429 —— 服务端限流，可稍后重试</li>
 *   <li>{@code AI_TIMEOUT} 504 —— 模型调用超时</li>
 *   <li>{@code AI_UPSTREAM_ERROR} 502 —— 上游 5xx / 网络异常</li>
 *   <li>{@code AI_INVALID_RESPONSE} 422 —— 模型返回无法安全解析的结构</li>
 * </ul>
 *
 * <p>所有 message 都不含密钥、不含完整请求体。
 */
public final class AiErrors {

    private AiErrors() {
    }

    public static ApiException notConfigured() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED",
                "AI 服务未配置：请在后端环境变量中设置 DEEPSEEK_API_KEY 后重启服务");
    }

    public static ApiException authFailed() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "AI_AUTH_FAILED",
                "模型服务鉴权失败，请确认 DEEPSEEK_API_KEY 有效且具备该模型权限");
    }

    public static ApiException rateLimited() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_RATE_LIMITED",
                "模型服务当前限流，请稍后重试");
    }

    public static ApiException timeout() {
        return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT",
                "模型调用超时，本次分析未完成，可重试");
    }

    public static ApiException upstream(String detail) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "AI_UPSTREAM_ERROR",
                "模型调用失败：" + detail);
    }

    public static ApiException invalidResponse(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_INVALID_RESPONSE",
                "模型返回内容无法作为核验建议使用：" + detail);
    }
}
