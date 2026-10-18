package com.example.opsaiagent.tools.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolErrorFormatter 单元测试
 * 覆盖：连接超时 / 连接失败 / 404 / 其他 4xx / 5xx / 未知异常
 */
@DisplayName("工具错误格式化")
class ToolErrorFormatterTest {

    private static final String SERVICE = "svc-a";
    private static final String URL = "http://svc-a:8080/health";

    @Test
    @DisplayName("连接超时：提示超时原因")
    void formatHttpError_timeout() {
        ResourceAccessException ex =
                new ResourceAccessException("timeout", new SocketTimeoutException("read timed out"));

        String message = ToolErrorFormatter.formatHttpError(SERVICE, URL, ex);

        assertThat(message).contains("超时");
        assertThat(message).contains(SERVICE);
        assertThat(message).contains(URL);
    }

    @Test
    @DisplayName("连接失败：提示进程未启动/网络不通")
    void formatHttpError_connectionFailed() {
        ResourceAccessException ex =
                new ResourceAccessException("connect", new ConnectException("Connection refused"));

        String message = ToolErrorFormatter.formatHttpError(SERVICE, URL, ex);

        assertThat(message).contains("连接失败");
        assertThat(message).contains("Connection refused");
    }

    @Test
    @DisplayName("404：提示端点不存在")
    void formatHttpError_notFound() {
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatusCode.valueOf(404), "Not Found",
                HttpHeaders.EMPTY, null, StandardCharsets.UTF_8);

        String message = ToolErrorFormatter.formatHttpError(SERVICE, URL, ex);

        assertThat(message).contains("404");
    }

    @Test
    @DisplayName("其他 4xx：提示状态码")
    void formatHttpError_otherClientError() {
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatusCode.valueOf(400), "Bad Request",
                HttpHeaders.EMPTY, null, StandardCharsets.UTF_8);

        String message = ToolErrorFormatter.formatHttpError(SERVICE, URL, ex);

        assertThat(message).contains("400 BAD_REQUEST");
    }

    @Test
    @DisplayName("5xx：提示服务端异常")
    void formatHttpError_serverError() {
        HttpServerErrorException ex = HttpServerErrorException.create(
                HttpStatusCode.valueOf(500), "Internal Server Error",
                HttpHeaders.EMPTY, null, StandardCharsets.UTF_8);

        String message = ToolErrorFormatter.formatHttpError(SERVICE, URL, ex);

        assertThat(message).contains("500 INTERNAL_SERVER_ERROR");
        assertThat(message).contains("服务端");
    }

    @Test
    @DisplayName("未知异常：返回原始错误信息")
    void formatHttpError_unknown() {
        Exception ex = new RuntimeException("boom");

        String message = ToolErrorFormatter.formatHttpError(SERVICE, URL, ex);

        assertThat(message).contains(SERVICE);
        assertThat(message).contains("boom");
    }
}
