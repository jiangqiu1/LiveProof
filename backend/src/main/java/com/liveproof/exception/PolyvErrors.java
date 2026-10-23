package com.liveproof.exception;

import org.springframework.http.HttpStatus;

/**
 * 保利威智能字幕相关的统一错误入口。
 *
 * <p>错误码是前端分支依据，不要匹配 message 文本：
 * <ul>
 *   <li>{@code POLYV_NOT_CONFIGURED} 503 —— 未配置 appId/appSecret，属部署状态</li>
 *   <li>{@code POLYV_AUTH_FAILED} 502 —— 签名错误 / 时间戳过期 / appId 不存在（10002/10003/20003）</li>
 *   <li>{@code POLYV_TIMEOUT} 504 —— 调用超时</li>
 *   <li>{@code POLYV_UPSTREAM_ERROR} 502 —— 上游 5xx / 网络异常 / 未识别错误码</li>
 *   <li>{@code POLYV_QUOTA_EXHAUSTED} 503 —— 资源点或余额不足（56809）</li>
 *   <li>{@code POLYV_INVALID_VID} 400 —— VID 非法（56800）</li>
 *   <li>{@code POLYV_TASK_NOT_FOUND} 404 —— 云端任务不存在或已删除（56810）</li>
 *   <li>{@code POLYV_TASK_PROCESSING} 409 —— 字幕仍在生成中（56812）</li>
 *   <li>{@code POLYV_SUBTITLE_INVALID} 422 —— 云端字幕正文未通过本地严格校验</li>
 *   <li>{@code SUBTITLE_TASK_NOT_FOUND} 404 —— 本地任务表没有这条登记</li>
 *   <li>{@code SUBTITLE_TASK_ALREADY_BOUND} 409 —— 该 taskId 已登记在别的视频上</li>
 *   <li>{@code POLYV_VID_NOT_CONFIGURED} 400 —— 视频没有配置对应 VID</li>
 *   <li>{@code POLYV_DURATION_NOT_CONFIGURED} 400 —— 视频没有配置时长基准，无法做越界校验</li>
 *   <li>{@code SMART_SUBTITLE_NOT_READY} 409 —— 请求用云端字幕，但没有就绪任务</li>
 * </ul>
 *
 * <p>所有 message 只含平台错误码与平台原始描述（已脱敏），不含密钥、签名、请求体。
 */
public final class PolyvErrors {

    private PolyvErrors() {
    }

    public static ApiException notConfigured() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "POLYV_NOT_CONFIGURED",
                "保利威智能字幕未配置：请在后端环境变量中设置 POLYV_LIVE_APP_ID 与 POLYV_LIVE_APP_SECRET 后重启");
    }

    public static ApiException authFailed(String platformCode) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "POLYV_AUTH_FAILED",
                "保利威签名校验未通过（" + platformCode + "）：请确认 appId/appSecret 匹配且服务器时间准确");
    }

    public static ApiException timeout() {
        return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "POLYV_TIMEOUT",
                "调用保利威超时，本次操作未取得结果，可重试");
    }

    public static ApiException upstream(String detail) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "POLYV_UPSTREAM_ERROR",
                "调用保利威失败：" + detail);
    }

    public static ApiException quotaExhausted(String detail) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "POLYV_QUOTA_EXHAUSTED",
                "保利威账号资源点或余额不足（56809）" + (detail == null || detail.isBlank() ? "" : "：" + detail));
    }

    public static ApiException invalidVid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "POLYV_INVALID_VID",
                "保利威不接受该 VID（56800）" + (detail == null || detail.isBlank() ? "" : "：" + detail));
    }

    public static ApiException taskNotFoundInPlatform() {
        return new ApiException(HttpStatus.NOT_FOUND, "POLYV_TASK_NOT_FOUND",
                "保利威侧不存在该字幕任务（56810），可能已被删除");
    }

    public static ApiException taskProcessing() {
        return new ApiException(HttpStatus.CONFLICT, "POLYV_TASK_PROCESSING",
                "字幕仍在生成中（56812），请稍后再取，不要重复创建任务");
    }

    public static ApiException invalidSubtitles(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "POLYV_SUBTITLE_INVALID",
                "云端字幕未通过校验：" + detail);
    }

    public static ApiException localTaskNotFound(String taskId) {
        return new ApiException(HttpStatus.NOT_FOUND, "SUBTITLE_TASK_NOT_FOUND",
                "本地未登记该字幕任务：" + taskId);
    }

    public static ApiException taskAlreadyBound(String taskId, String video) {
        return new ApiException(HttpStatus.CONFLICT, "SUBTITLE_TASK_ALREADY_BOUND",
                "字幕任务 " + taskId + " 已登记在视频 " + video + " 上，不能重复登记到其它视频");
    }

    public static ApiException vidNotConfigured(String video) {
        return new ApiException(HttpStatus.BAD_REQUEST, "POLYV_VID_NOT_CONFIGURED",
                "视频 " + video + " 未配置对应的保利威 VID，拒绝猜测");
    }

    public static ApiException durationNotConfigured(String video) {
        return new ApiException(HttpStatus.BAD_REQUEST, "POLYV_DURATION_NOT_CONFIGURED",
                "视频 " + video + " 未配置时长基准，无法校验字幕时间是否越界，拒绝跳过校验");
    }

    /**
     * 请求使用云端字幕，但本地没有该视频的就绪任务。
     *
     * <p>不用空字幕降级：AI 核验若拿到空字幕，会给出「无相关内容」式结论，
     * 而真实情况是「云端字幕还没准备」，两者语义完全不同。
     */
    public static ApiException subtitleNotReady(String video) {
        return new ApiException(HttpStatus.CONFLICT, "SMART_SUBTITLE_NOT_READY",
                "视频 " + video + " 还没有就绪的智能字幕任务：请先在素材页创建或查询任务，再切换为该来源");
    }
}
