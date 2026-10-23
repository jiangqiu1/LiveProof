package com.liveproof.exception;

import org.springframework.http.HttpStatus;

/**
 * 资源不存在：用例 / 待办 / 媒体。
 */
public class NotFoundException extends ApiException {

    private NotFoundException(String code, String message) {
        super(HttpStatus.NOT_FOUND, code, message);
    }

    public static NotFoundException caseNotFound(String id) {
        return new NotFoundException("CASE_NOT_FOUND", "用例不存在：" + id);
    }

    public static NotFoundException todoNotFound(Long id) {
        return new NotFoundException("TODO_NOT_FOUND", "待办不存在：" + id);
    }

    /** 媒体拒绝一律返回同一个错误，不暴露白名单外是否存在该文件。 */
    public static NotFoundException mediaNotFound(String name) {
        return new NotFoundException("MEDIA_NOT_FOUND", "媒体不可用：" + name);
    }

    public static NotFoundException subtitleNotFound(String video) {
        return new NotFoundException("SUBTITLE_NOT_FOUND", "字幕不可用：" + video);
    }
}
