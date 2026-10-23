package com.liveproof.exception;

import org.springframework.http.HttpStatus;

/**
 * 素材相关错误的统一构造入口。
 *
 * <p>错误码是前端分支依据，不要匹配 message 文本。
 * <ul>
 *   <li>{@code MATERIAL_NOT_FOUND} 404 —— id 不存在</li>
 *   <li>{@code MATERIAL_NOT_READY} 409 —— 转码未完成 / 失败，此时不能建核验事项或取播放地址</li>
 *   <li>{@code MATERIAL_IN_USE} 409 —— 仍有核验事项引用</li>
 *   <li>{@code BUILTIN_MATERIAL} 409 —— 内置示例禁止删除</li>
 *   <li>{@code MATERIAL_INVALID_FILE} 422 —— 扩展名/魔数/大小不合规</li>
 *   <li>{@code MATERIAL_LIMIT_EXCEEDED} 422 —— 素材数量上限</li>
 *   <li>{@code POLYV_UPLOAD_NOT_CONFIGURED} 503 —— 缺少 writeToken，属部署状态</li>
 *   <li>{@code POLYV_UPLOAD_FAILED} 502 —— 转发到保利威失败</li>
 *   <li>{@code POLYV_UPLOAD_TIMEOUT} 504 —— 上传超时</li>
 *   <li>{@code POLYV_SNAPSHOT_FAILED} 502 —— 云端截图任务失败</li>
 * </ul>
 *
 * <p>所有 message 都不含 writeToken / secretkey / 原始报文。
 */
public final class MaterialErrors {

    private MaterialErrors() {
    }

    public static ApiException notFound(String id) {
        return new ApiException(HttpStatus.NOT_FOUND, "MATERIAL_NOT_FOUND", "素材不存在：" + id);
    }

    public static ApiException notReady(String id, String status) {
        return new ApiException(HttpStatus.CONFLICT, "MATERIAL_NOT_READY",
                "素材尚未就绪（当前状态：" + status + "）：" + id);
    }

    public static ApiException inUse(int count) {
        return new ApiException(HttpStatus.CONFLICT, "MATERIAL_IN_USE",
                "该素材仍被 " + count + " 条核验事项引用，请先删除这些事项");
    }

    public static ApiException builtin() {
        return new ApiException(HttpStatus.CONFLICT, "BUILTIN_MATERIAL", "内置示例素材不可删除");
    }

    public static ApiException invalidFile(String reason) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MATERIAL_INVALID_FILE", reason);
    }

    public static ApiException limitExceeded(int max) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MATERIAL_LIMIT_EXCEEDED",
                "素材数量已达上限 " + max + " 个，请先删除不再使用的素材");
    }

    public static ApiException uploadNotConfigured() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "POLYV_UPLOAD_NOT_CONFIGURED",
                "保利威上传未配置：请在后端环境变量中设置 POLYV_VOD_WRITE_TOKEN 后重启服务");
    }

    public static ApiException uploadFailed(int code, String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "POLYV_UPLOAD_FAILED",
                "上传到保利威失败（错误码 " + code + "）：" + message);
    }

    public static ApiException uploadTimeout() {
        return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "POLYV_UPLOAD_TIMEOUT",
                "上传到保利威超时，文件未完成上传，可重试");
    }

    public static ApiException snapshotFailed(String reason) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "POLYV_SNAPSHOT_FAILED",
                "保利威截图任务失败：" + reason);
    }
}
