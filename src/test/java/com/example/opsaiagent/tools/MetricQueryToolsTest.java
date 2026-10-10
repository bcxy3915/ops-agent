package com.example.opsaiagent.tools;

import com.example.opsaiagent.registry.ServiceInfo;
import com.example.opsaiagent.registry.ServiceRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * MetricQueryTools 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("指标查询工具")
class MetricQueryToolsTest {

    @Mock
    private ServiceRegistry serviceRegistry;

    private MetricQueryTools tools;
    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
        tools = new MetricQueryTools(serviceRegistry);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ServiceInfo svc(String name) {
        ServiceInfo info = new ServiceInfo();
        info.setName(name);
        info.setBaseUrl("http://localhost:" + port);
        info.setMetricsPath("/actuator/metrics");
        return info;
    }

    private void mockResponse(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    // ==================== 正常 ====================

    @Test
    @DisplayName("指标查询：返回 measurements")
    void queryMetric_success() {
        mockResponse("/actuator/metrics/system.cpu.usage", 200,
                "{\"name\":\"system.cpu.usage\",\"measurements\":[{\"statistic\":\"VALUE\",\"value\":0.42}]}");
        when(serviceRegistry.getByName("todo-service")).thenReturn(svc("todo-service"));

        String result = tools.queryMetric("todo-service", "system.cpu.usage");

        assertThat(result)
                .contains("todo-service")
                .contains("system.cpu.usage")
                .contains("0.42");
    }

    @Test
    @DisplayName("指标查询：无 measurements 字段")
    void queryMetric_noMeasurements() {
        mockResponse("/actuator/metrics/foo.bar", 200, "{\"name\":\"foo.bar\"}");
        when(serviceRegistry.getByName("todo-service")).thenReturn(svc("todo-service"));

        String result = tools.queryMetric("todo-service", "foo.bar");

        assertThat(result).contains("无数据");
    }

    // ==================== 服务未注册 ====================

    @Test
    @DisplayName("服务未注册：返回提示")
    void queryMetric_unregistered() {
        when(serviceRegistry.getByName("unknown")).thenReturn(null);

        String result = tools.queryMetric("unknown", "system.cpu.usage");

        assertThat(result).contains("未注册");
    }

    // ==================== 指标不存在 ====================

    @Test
    @DisplayName("指标 404：返回友好提示")
    void queryMetric_notFound() {
        mockResponse("/actuator/metrics/nonexistent.metric", 404, "{}");
        when(serviceRegistry.getByName("todo-service")).thenReturn(svc("todo-service"));

        String result = tools.queryMetric("todo-service", "nonexistent.metric");

        assertThat(result)
                .contains("不存在")
                .contains("/actuator/metrics");
    }

    // ==================== 连接失败 ====================

    @Test
    @DisplayName("服务连接失败：返回错误提示")
    void queryMetric_connectionRefused() {
        ServiceInfo info = new ServiceInfo();
        info.setName("dead-service");
        info.setBaseUrl("http://localhost:1");
        info.setMetricsPath("/actuator/metrics");
        when(serviceRegistry.getByName("dead-service")).thenReturn(info);

        String result = tools.queryMetric("dead-service", "system.cpu.usage");

        assertThat(result).isNotBlank();
    }
}