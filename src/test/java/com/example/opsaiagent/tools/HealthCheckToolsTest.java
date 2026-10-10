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
 * HealthCheckTools 单元测试
 * 由于 RestClient 是 RestClient.create() 硬编码的，用 JDK 内置 HttpServer 起真实 mock。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("健康检查工具")
class HealthCheckToolsTest {

    @Mock
    private ServiceRegistry serviceRegistry;

    private HealthCheckTools tools;
    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
        tools = new HealthCheckTools(serviceRegistry);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // ==================== 辅助 ====================

    private ServiceInfo svc(String name, String healthPath) {
        ServiceInfo info = new ServiceInfo();
        info.setName(name);
        info.setBaseUrl("http://localhost:" + port);
        info.setHealthPath(healthPath);
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
    @DisplayName("健康查询：服务返回 UP")
    void queryServiceHealth_up() {
        mockResponse("/actuator/health", 200, "{\"status\":\"UP\"}");
        when(serviceRegistry.getByName("todo-service"))
                .thenReturn(svc("todo-service", "/actuator/health"));

        String result = tools.queryServiceHealth("todo-service");

        assertThat(result)
                .contains("todo-service")
                .contains("UP");
    }

    @Test
    @DisplayName("健康查询：服务返回 DOWN")
    void queryServiceHealth_down() {
        mockResponse("/actuator/health", 200, "{\"status\":\"DOWN\"}");
        when(serviceRegistry.getByName("todo-service"))
                .thenReturn(svc("todo-service", "/actuator/health"));

        String result = tools.queryServiceHealth("todo-service");

        assertThat(result).contains("DOWN");
    }

    // ==================== 服务未注册 ====================

    @Test
    @DisplayName("服务未注册：返回提示和可用服务列表")
    void queryServiceHealth_unregistered() {
        ServiceInfo other = svc("svc-a", "/actuator/health");
        when(serviceRegistry.getByName("unknown")).thenReturn(null);
        when(serviceRegistry.listAll()).thenReturn(List.of(other));

        String result = tools.queryServiceHealth("unknown");

        assertThat(result)
                .contains("未注册")
                .contains("svc-a");
    }

    @Test
    @DisplayName("服务未注册：可用列表为空")
    void queryServiceHealth_unregisteredEmptyList() {
        when(serviceRegistry.getByName("unknown")).thenReturn(null);
        when(serviceRegistry.listAll()).thenReturn(List.of());

        String result = tools.queryServiceHealth("unknown");

        assertThat(result).contains("未注册");
    }

    // ==================== HTTP 异常 ====================

    @Test
    @DisplayName("服务连接失败：返回错误提示")
    void queryServiceHealth_connectionRefused() {
        ServiceInfo info = new ServiceInfo();
        info.setName("dead-service");
        info.setBaseUrl("http://localhost:1");  // 不存在的端口
        info.setHealthPath("/actuator/health");
        when(serviceRegistry.getByName("dead-service")).thenReturn(info);

        String result = tools.queryServiceHealth("dead-service");

        assertThat(result)
                .contains("dead-service")
                .doesNotContain("UP");
    }
}