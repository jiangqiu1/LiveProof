package com.liveproof.exception;

import org.springframework.http.HttpStatus;

/**
 * 请求参数/请求体不合法（业务层校验失败）。
 */
public class BadRequestException extends ApiException {

    public BadRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }

    public static BadRequestException emptyPatch() {
        return new BadRequestException("NO_UPDATABLE_FIELD", "请求体未包含任何可更新字段");
    }

    public static BadRequestException invalidParameter(String message) {
        return new BadRequestException("INVALID_PARAMETER", message);
    }
}
