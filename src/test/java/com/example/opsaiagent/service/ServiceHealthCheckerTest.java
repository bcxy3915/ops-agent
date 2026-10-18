package com.example.opsaiagent.service;

import com.example.opsaiagent.entity.OpsServiceEntity;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.registry.ServiceInfo;
import com.example.opsaiagent.registry.ServiceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * ServiceHealthChecker 单元测试
 * 覆盖：健康检查 UP / DOWN / 连接失败降级 / 服务不存在抛业务异常
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("服务健康检查")
class ServiceHealthCheckerTest {

    @Mock
    private OpsServiceManager serviceManager;

    @Mock
    private ServiceRegistry serviceRegistry;

    @InjectMocks
    private ServiceHealthChecker checker;

    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        ReflectionTestUtils.setField(checker, "restClient", restClient);
    }

    private ServiceInfo info(String name) {
        ServiceInfo info = new ServiceInfo();
        info.setName(name);
        info.setBaseUrl("http://" + name + ":8080");
        info.setHealthPath("/actuator/health");
        return info;
    }

    private OpsServiceEntity entity(String name) {
        OpsServiceEntity e = new OpsServiceEntity();
        e.setId("id-" + name);
        e.setName(name);
        return e;
    }

    @Test
    @DisplayName("checkNow：返回 UP 并更新数据库状态")
    void checkNow_up() {
        when(serviceRegistry.getByName("svc-a")).thenReturn(info("svc-a"));
        when(serviceManager.findByName("svc-a")).thenReturn(Optional.of(entity("svc-a")));
        server.expect(requestTo("http://svc-a:8080/actuator/health"))
                .andRespond(withSuccess("{\"status\":\"UP\"}", MediaType.APPLICATION_JSON));

        String status = checker.checkNow("svc-a");

        assertThat(status).isEqualTo("UP");
        verify(serviceManager).updateStatus("id-svc-a", "UP");
    }

    @Test
    @DisplayName("checkNow：响应非 UP 时返回 DOWN")
    void checkNow_down() {
        when(serviceRegistry.getByName("svc-a")).thenReturn(info("svc-a"));
        when(serviceManager.findByName("svc-a")).thenReturn(Optional.of(entity("svc-a")));
        server.expect(requestTo("http://svc-a:8080/actuator/health"))
                .andRespond(withSuccess("{\"status\":\"DOWN\"}", MediaType.APPLICATION_JSON));

        String status = checker.checkNow("svc-a");

        assertThat(status).isEqualTo("DOWN");
        verify(serviceManager).updateStatus("id-svc-a", "DOWN");
    }

    @Test
    @DisplayName("checkNow：连接失败返回 DOWN 并更新状态")
    void checkNow_connectionError() {
        when(serviceRegistry.getByName("svc-a")).thenReturn(info("svc-a"));
        when(serviceManager.findByName("svc-a")).thenReturn(Optional.of(entity("svc-a")));
        server.expect(requestTo("http://svc-a:8080/actuator/health"))
                .andRespond(withServerError());

        String status = checker.checkNow("svc-a");

        assertThat(status).isEqualTo("DOWN");
        verify(serviceManager).updateStatus("id-svc-a", "DOWN");
    }

    @Test
    @DisplayName("checkNow：服务不存在抛业务异常")
    void checkNow_serviceNotFound() {
        when(serviceRegistry.getByName("unknown")).thenReturn(null);

        assertThatThrownBy(() -> checker.checkNow("unknown"))
                .isInstanceOf(BusinessException.class);
    }
}
