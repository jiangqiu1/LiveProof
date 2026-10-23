package com.liveproof.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一错误结构：{"error":{"code":"CASE_NOT_FOUND","message":"用例不存在"}}
 */
public record ErrorResponse(ErrorBody error) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code, String message, Object details) {
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new ErrorBody(code, message, null));
    }

    public static ErrorResponse of(String code, String message, Object details) {
        return new ErrorResponse(new ErrorBody(code, message, details));
    }
}
