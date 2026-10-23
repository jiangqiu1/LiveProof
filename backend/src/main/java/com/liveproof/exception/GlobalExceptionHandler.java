package com.liveproof.exception;

import com.liveproof.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;

/**
 * 全局异常处理：所有错误统一为 {"error":{"code","message"}}。
 *
 * <p>同时修正旧 Node 实现的两个缺陷：
 * <ul>
 *   <li>D1：数据库异常原先会终止进程，现返回 500 JSON 且服务存活</li>
 *   <li>D5：错误响应结构原先不统一，现全部为同一 JSON 结构</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ErrorResponse.of(ex.getCode(), ex.getMessage()));
    }

    /** Bean Validation 失败（@Valid 请求体）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> details = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toFieldDetail)
                .toList();
        String message = details.isEmpty() ? "参数校验失败" : details.get(0).get("message");
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("VALIDATION_ERROR", message, details));
    }

    private static Map<String, String> toFieldDetail(FieldError error) {
        return Map.of(
                "field", error.getField(),
                "message", error.getDefaultMessage() == null ? "非法取值" : error.getDefaultMessage());
    }

    /** 请求体不是合法 JSON，或枚举取值非法（由 Jackson 包装抛出）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        String root = rootCauseMessage(ex);
        String message = root != null && root.contains("非法取值")
                ? root
                : "请求体格式错误，无法解析 JSON";
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("INVALID_JSON", message));
    }

    private static String rootCauseMessage(Throwable ex) {
        Throwable current = ex;
        String message = null;
        while (current != null) {
            if (current instanceof IllegalArgumentException) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message != null ? message : ex.getCause() != null ? ex.getCause().getMessage() : null;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "INVALID_PARAMETER", "参数 " + ex.getName() + " 取值非法"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                "MISSING_PARAMETER", "缺少必填参数：" + ex.getParameterName()));
    }

    @ExceptionHandler(NotSatisfiableRangeException.class)
    public ResponseEntity<ErrorResponse> handleRange(NotSatisfiableRangeException ex) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header("Content-Range", "bytes */" + ex.getTotal())
                .body(ErrorResponse.of("RANGE_NOT_SATISFIABLE", "请求的字节范围不可满足"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ErrorResponse.of("METHOD_NOT_ALLOWED", "不支持的请求方法：" + ex.getMethod()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("NOT_FOUND", "资源不存在"));
    }

    /**
     * 数据库异常：返回 500，进程存活，请求不挂起。
     * 响应体不包含 SQL、连接串或堆栈信息。
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(DataAccessException ex) {
        log.error("数据库访问失败：{}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("DATABASE_ERROR", "数据库访问失败，请检查数据库连接"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("未处理异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "服务内部错误"));
    }
}
