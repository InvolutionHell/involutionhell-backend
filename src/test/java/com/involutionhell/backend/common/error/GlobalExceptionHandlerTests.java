package com.involutionhell.backend.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.involutionhell.backend.common.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;

class GlobalExceptionHandlerTests {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleNotLoginExceptionReturnsUnauthorized() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleNotLoginException(
                new NotLoginException("未登录", "login", NotLoginException.NOT_TOKEN)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().message()).isEqualTo("未提供 Token");
    }

    @Test
    void handleNotPermissionExceptionReturnsForbidden() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleNotPermissionException(
                new NotPermissionException("p1")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().message()).contains("拒绝访问: 缺少权限 [p1]");
    }

    @Test
    void handleNotRoleExceptionReturnsForbidden() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleNotRoleException(
                new NotRoleException("admin")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().message()).contains("拒绝访问: 缺少角色 [admin]");
    }

    @Test
    void handleValidationReturnsFirstMethodArgumentError() throws Exception {
        ValidationTarget target = new ValidationTarget();
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(target, "target");
        bindingResult.rejectValue("username", "NotBlank", "用户名不能为空");

        Method method = GlobalExceptionHandlerTests.class.getDeclaredMethod("dummyValidatedMethod", ValidationTarget.class);
        MethodArgumentNotValidException exception =
                new MethodArgumentNotValidException(new MethodParameter(method, 0), bindingResult);

        ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("username: 用户名不能为空");
    }

    @Test
    void handleValidationReturnsFirstBindError() {
        ValidationTarget target = new ValidationTarget();
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(target, "target");
        bindingResult.rejectValue("username", "NotBlank", "用户名不能为空");

        ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(new BindException(bindingResult));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("username: 用户名不能为空");
    }

    @Test
    void handleValidationReturnsConstraintViolationMessage() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        ConstraintViolationException exception = new ConstraintViolationException(validator.validate(new ValidationTarget()));

        ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("username");
        assertThat(response.getBody().message()).contains("用户名不能为空");
    }

    @Test
    void handleValidationFallsBackToDefaultMessage() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleValidation(new Exception("unknown"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("请求参数不合法");
    }

    @Test
    void handleBusinessReturnsBadRequest() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(new IllegalStateException("账号已禁用"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("账号已禁用");
    }

    @Test
    void handleUnexpectedReturnsInternalServerError() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpected(new RuntimeException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().message()).isEqualTo("服务器内部错误");
    }

    private void dummyValidatedMethod(ValidationTarget target) {
    }

    private static final class ValidationTarget {

        @NotBlank(message = "用户名不能为空")
        private String username;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }
    }
}
