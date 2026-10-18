package com.example.opsaiagent.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiAuthenticationEntryPoint 单元测试
 * 覆盖：401 JSON 响应写入
 */
@DisplayName("API 认证异常入口")
class ApiAuthenticationEntryPointTest {

    @Test
    @DisplayName("commence：写入 401 JSON 响应")
    void commence_writesUnauthorizedJson() throws Exception {
        ApiAuthenticationEntryPoint entryPoint = new ApiAuthenticationEntryPoint(new ObjectMapper());
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        entryPoint.commence(request, response, new BadCredentialsException("bad token"));

        verify(response).setStatus(401);
        String body = writer.toString();
        assertThat(body).contains("\"code\":401");
        assertThat(body).contains("未认证或 Token 无效");
    }
}
