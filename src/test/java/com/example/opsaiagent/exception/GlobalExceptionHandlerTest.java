package com.example.opsaiagent.exception;

import com.example.opsaiagent.dto.ApiResponse;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.ratelimit.exception.RateLimitExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GlobalExceptionHandler 单元测试
 * 覆盖：限流 429 / 权限 403 / 业务异常 / 参数校验 / 兜底异常
 */
@DisplayName("全局异常处理")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("限流异常：返回 429 与错误码")
    void rateLimit_returns429() {
        ResponseEntity<ApiResponse<Void>> result =
                handler.handleRateLimit(new RateLimitExceededException("请求过于频繁"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(result.getBody().getCode()).isEqualTo(429);
        assertThat(result.getBody().getMessage()).contains("请求过于频繁");
    }

    @Test
    @DisplayName("权限不足：返回 403")
    void accessDenied_returns403() {
        ResponseEntity<ApiResponse<Void>> result =
                handler.handleAccessDenied(new AccessDeniedException("denied"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(result.getBody().getCode()).isEqualTo(403);
        assertThat(result.getBody().getMessage()).contains("权限不足");
    }

    @Test
    @DisplayName("业务异常：返回对应错误码")
    void business_returnsErrorCode() {
        ApiResponse<Void> result =
                handler.handleBusiness(new BusinessException(ErrorCode.SERVICE_NOT_FOUND, "服务不存在: svc"));

        assertThat(result.getCode()).isEqualTo(1001);
        assertThat(result.getMessage()).contains("svc");
    }

    @Test
    @DisplayName("参数校验异常：拼接字段错误信息")
    void validation_joinsFieldErrors() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(
                new FieldError("req", "username", "用户名不能为空"),
                new FieldError("req", "password", "密码长度 6-64 位")
        ));

        ApiResponse<Void> result = handler.handleValidation(ex);

        assertThat(result.getCode()).isEqualTo(ErrorCode.INVALID_PARAMETER.getCode());
        assertThat(result.getMessage()).contains("username: 用户名不能为空")
                .contains("password: 密码长度 6-64 位");
    }

    @Test
    @DisplayName("兜底异常：返回内部错误")
    void all_returnsInternalError() {
        ApiResponse<Void> result = handler.handleAll(new RuntimeException("boom"));

        assertThat(result.getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.getCode());
        assertThat(result.getMessage()).contains("boom");
    }
}
