package com.liveproof.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务异常基类：携带 HTTP 状态与稳定错误码，由全局处理器统一转成 JSON。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
