package com.example.opsaiagent.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiAccessDeniedHandler 单元测试
 * 覆盖：403 JSON 响应写入
 */
@DisplayName("API 权限拒绝处理")
class ApiAccessDeniedHandlerTest {

    @Test
    @DisplayName("handle：写入 403 JSON 响应")
    void handle_writesForbiddenJson() throws Exception {
        ApiAccessDeniedHandler handler = new ApiAccessDeniedHandler(new ObjectMapper());
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        handler.handle(request, response, new AccessDeniedException("denied"));

        verify(response).setStatus(403);
        String body = writer.toString();
        assertThat(body).contains("\"code\":403");
        assertThat(body).contains("权限不足，无法执行该操作");
    }
}
