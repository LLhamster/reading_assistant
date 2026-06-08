package com.example.httpreading.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ==================== 业务异常 ====================

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<CommonResponse<Void>> handleBusiness(BusinessException e) {
        log.warn("业务异常 - code:{}, message:{}", e.getCode(), e.getMessage());
        ErrorCode ec = ErrorCode.fromCode(e.getCode());
        int httpStatus = ec.getHttpStatus();
        CommonResponse<Void> body = CommonResponse.error(ec, e.getMessage());
        return ResponseEntity.status(httpStatus).body(body);
    }

    // ==================== 参数相关异常 ====================

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<CommonResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数异常 - message:{}", e.getMessage());
        if (e.getMessage() != null && (e.getMessage().contains("未登录") || e.getMessage().contains("session"))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(CommonResponse.error(ErrorCode.UNAUTHORIZED));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(CommonResponse.error(ErrorCode.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CommonResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldError() != null
                ? e.getBindingResult().getFieldError().getDefaultMessage()
                : "参数校验失败";
        log.warn("参数校验失败 - message:{}", msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(CommonResponse.error(ErrorCode.VALIDATION_ERROR, msg));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<CommonResponse<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().iterator().next().getMessage();
        log.warn("约束校验失败 - message:{}", msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(CommonResponse.error(ErrorCode.VALIDATION_ERROR, msg));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<CommonResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件过大 - message:{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(CommonResponse.error(ErrorCode.BAD_REQUEST, "上传文件过大，请检查 spring.servlet.multipart 上传大小配置"));
    }

    // ==================== 认证/权限异常 ====================

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<CommonResponse<Void>> handleAuth(AuthenticationException e) {
        log.warn("认证失败 - type:{}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(CommonResponse.error(ErrorCode.UNAUTHORIZED));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<CommonResponse<Void>> handleForbidden(AccessDeniedException e) {
        log.warn("权限不足 - type:{}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(CommonResponse.error(ErrorCode.FORBIDDEN));
    }

    // ==================== 兜底异常 ====================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommonResponse<Void>> handleOther(Exception e) {
        log.error("系统异常 - type:{}, message:{}", e.getClass().getSimpleName(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(CommonResponse.error(ErrorCode.INTERNAL_ERROR));
    }
}
