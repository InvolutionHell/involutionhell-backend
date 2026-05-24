package com.involutionhell.backend.common.error;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.involutionhell.backend.analytics.service.Ga4UnavailableException;
import com.involutionhell.backend.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.validation.ConstraintViolationException;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ==========================================
    // Analytics 异常拦截
    // ==========================================

    @ExceptionHandler(Ga4UnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleGa4Unavailable(Ga4UnavailableException e) {
        // 带上异常对象让 logger 打印完整堆栈，便于排查 gRPC/超时/凭证失效等根因
        log.error("GA4 服务不可用", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail("数据分析服务暂时不可用，请稍后重试"));
    }

    // ==========================================
    // Sa-Token 异常拦截
    // ==========================================

    /**
     * Sa-Token: 拦截未登录异常
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotLoginException(NotLoginException e) {
        // 判断场景值，定制化异常信息
        String message = switch (e.getType()) {
            case NotLoginException.NOT_TOKEN -> "未提供 Token";
            case NotLoginException.INVALID_TOKEN -> "Token 无效";
            case NotLoginException.TOKEN_TIMEOUT -> "Token 已过期";
            case NotLoginException.BE_REPLACED -> "Token 已被顶下线";
            case NotLoginException.KICK_OUT -> "Token 已被踢下线";
            case NotLoginException.TOKEN_FREEZE -> "Token 已被冻结";
            case NotLoginException.NO_PREFIX -> "未按照指定前缀提交 Token";
            default -> "当前会话未登录";
        };
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail(message));
    }

    /**
     * Sa-Token: 拦截权限不足异常。
     *
     * 注意要用 e.getPermission() 而不是 e.getCode()。
     * getCode() 是父类 SaTokenException 的方法，返回的是整数场景码（比如 -1），
     * 而权限字符串（比如 "user:center:read"）在 NotPermissionException 自己的 permission 字段里，
     * 要调 getPermission() 才能拿到。用错了的话错误消息会变成 "拒绝访问: 缺少权限 [-1]"，没有任何意义。
     */
    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotPermissionException(NotPermissionException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail("拒绝访问: 缺少权限 [" + e.getPermission() + "]"));
    }

    /**
     * Sa-Token: 拦截角色不足异常
     */
    @ExceptionHandler(NotRoleException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotRoleException(NotRoleException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail("拒绝访问: 缺少角色 [" + e.getRole() + "]"));
    }

    /**
     * 业务归属访问被拒——例如写入他人的 chat 历史、修改他人的资源。
     * 与 Sa-Token 缺权限/缺角色不同，message 由抛出方决定，403 状态码。
     */
    @ExceptionHandler(AccessDeniedBusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedBusiness(AccessDeniedBusinessException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail(e.getMessage()));
    }

    /**
     * 业务资源不存在（404）。
     * 区别于 IllegalArgumentException（400 参数错误）：资源找不到是 404，不是请求格式问题。
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(e.getMessage()));
    }

    // ==========================================
    // 业务与通用异常拦截
    // ==========================================

    /**
     * 将参数校验异常转换为 400 响应
     */
    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleValidation(Exception exception) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(resolveValidationMessage(exception)));
    }

    /**
     * 将业务校验异常转换为 400 响应
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiResponse<Void>> handleBusiness(Exception exception) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(exception.getMessage()));
    }

    /**
     * 兜底处理未预期异常并返回 500 响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        // 走 SLF4J 统一进日志管道（含 trace id / 采集到 Loki 等），不再用 printStackTrace 污染 stdout
        log.error("未处理的异常", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("服务器内部错误"));
    }

    /**
     * 提取不同校验异常中的首个可读错误信息
     */
    private String resolveValidationMessage(Exception exception) {
        if (exception instanceof MethodArgumentNotValidException methodArgumentNotValidException) {
            return Optional.ofNullable(methodArgumentNotValidException.getBindingResult().getFieldError())
                    .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                    .orElse("请求参数不合法");
        }
        if (exception instanceof BindException bindException) {
            return Optional.ofNullable(bindException.getBindingResult().getFieldError())
                    .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                    .orElse("请求参数不合法");
        }
        if (exception instanceof ConstraintViolationException constraintViolationException) {
            return constraintViolationException.getConstraintViolations().stream()
                    .findFirst()
                    .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                    .orElse("请求参数不合法");
        }
        return "请求参数不合法";
    }
}