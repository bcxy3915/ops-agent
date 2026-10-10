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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * MetricDiscoveryTools 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("指标发现工具")
class MetricDiscoveryToolsTest {

    @Mock
    private ServiceRegistry serviceRegistry;

    private MetricDiscoveryTools tools;
    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
        tools = new MetricDiscoveryTools(serviceRegistry);
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
    @DisplayName("正常：返回过滤后的关键指标")
    void listMetrics_success() {
        mockResponse("/actuator/metrics", 200,
                "{\"names\":[\"system.cpu.usage\",\"jvm.memory.used\",\"http.server.requests\","
                + "\"jvm.gc.pause\",\"hikaricp.connections.active\",\"process.uptime\",\"some.other.metric\"]}");
        when(serviceRegistry.getByName("todo-service")).thenReturn(svc("todo-service"));

        String result = tools.listMetrics("todo-service");

        assertThat(result)
                .contains("todo-service")
                .contains("system.cpu.usage")
                .contains("jvm.memory.used")
                .contains("http.server.requests")
                .contains("hikaricp.connections.active")
                .contains("process.uptime")
                .doesNotContain("some.other.metric"); // 被过滤掉
    }

    @Test
    @DisplayName("正常：超过 30 条会截断")
    void listMetrics_truncated() {
        StringBuilder names = new StringBuilder("[");
        for (int i = 0; i < 50; i++) {
            if (i > 0) names.append(",");
            names.append("\"system.metric").append(i).append("\"");
        }
        names.append("]");

        mockResponse("/actuator/metrics", 200, "{\"names\":" + names + "}");
        when(serviceRegistry.getByName("todo-service")).thenReturn(svc("todo-service"));

        String result = tools.listMetrics("todo-service");

        assertThat(result).contains("共支持 50 个指标");
        assertThat(result).contains("关键指标（共 30 个）");
    }

    @Test
    @DisplayName("无 names 字段：返回提示")
    void listMetrics_noNames() {
        mockResponse("/actuator/metrics", 200, "{}");
        when(serviceRegistry.getByName("todo-service")).thenReturn(svc("todo-service"));

        String result = tools.listMetrics("todo-service");

        assertThat(result).contains("未返回指标列表");
    }

    // ==================== 服务未注册 ====================

    @Test
    @DisplayName("服务未注册：返回提示和可用服务列表")
    void listMetrics_unregistered() {
        ServiceInfo other = svc("svc-a");
        when(serviceRegistry.getByName("unknown")).thenReturn(null);
        when(serviceRegistry.listAll()).thenReturn(List.of(other));

        String result = tools.listMetrics("unknown");

        assertThat(result)
                .contains("未注册")
                .contains("svc-a");
    }

    // ==================== 异常 ====================

    @Test
    @DisplayName("服务连接失败：返回错误提示")
    void listMetrics_connectionRefused() {
        ServiceInfo info = new ServiceInfo();
        info.setName("dead-service");
        info.setBaseUrl("http://localhost:1");
        info.setMetricsPath("/actuator/metrics");
        when(serviceRegistry.getByName("dead-service")).thenReturn(info);

        String result = tools.listMetrics("dead-service");

        assertThat(result).isNotBlank();
    }
}