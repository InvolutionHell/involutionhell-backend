package com.involutionhell.backend.common.error;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.involutionhell.backend.common.api.ApiResponse;
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

    /**
     * 将未登录异常转换为 401 响应
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotLogin(NotLoginException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail("未登录或登录状态已失效"));
    }

    /**
     * 将权限不足异常转换为 403 响应
     */
    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotPermission(NotPermissionException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail("无权限访问: " + exception.getPermission()));
    }

    /**
     * 将角色不足异常转换为 403 响应
     */
    @ExceptionHandler(NotRoleException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotRole(NotRoleException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail("缺少角色: " + exception.getRole()));
    }

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
