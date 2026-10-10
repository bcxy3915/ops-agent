package com.example.opsaiagent.registry;

import com.example.opsaiagent.entity.OpsServiceEntity;
import com.example.opsaiagent.service.OpsServiceManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * DbServiceRegistry 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("数据库服务注册表")
class DbServiceRegistryTest {

    @Mock
    private OpsServiceManager serviceManager;

    @InjectMocks
    private DbServiceRegistry registry;

    private OpsServiceEntity entity(String name, String baseUrl) {
        OpsServiceEntity e = new OpsServiceEntity();
        e.setName(name);
        e.setBaseUrl(baseUrl);
        e.setHealthPath("/actuator/health");
        e.setMetricsPath("/actuator/metrics");
        e.setOwner("ops-team");
        e.setEnv("prod");
        return e;
    }

    // ==================== listAll ====================

    @Test
    @DisplayName("listAll：返回全部服务并转换字段")
    void listAll() {
        when(serviceManager.listAll()).thenReturn(List.of(
                entity("svc-a", "http://a:8080"),
                entity("svc-b", "http://b:8080")
        ));

        List<ServiceInfo> list = registry.listAll();

        assertThat(list).hasSize(2);
        assertThat(list.get(0).getName()).isEqualTo("svc-a");
        assertThat(list.get(0).getBaseUrl()).isEqualTo("http://a:8080");
        assertThat(list.get(0).getHealthPath()).isEqualTo("/actuator/health");
        assertThat(list.get(0).getMetricsPath()).isEqualTo("/actuator/metrics");
        assertThat(list.get(0).getOwner()).isEqualTo("ops-team");
        assertThat(list.get(0).getEnv()).isEqualTo("prod");
    }

    @Test
    @DisplayName("listAll：空列表")
    void listAllEmpty() {
        when(serviceManager.listAll()).thenReturn(List.of());

        assertThat(registry.listAll()).isEmpty();
    }

    // ==================== getByName ====================

    @Test
    @DisplayName("getByName：找到服务并转换")
    void getByNameFound() {
        when(serviceManager.findByName("svc-a"))
                .thenReturn(Optional.of(entity("svc-a", "http://a:8080")));

        ServiceInfo info = registry.getByName("svc-a");

        assertThat(info).isNotNull();
        assertThat(info.getName()).isEqualTo("svc-a");
        assertThat(info.getBaseUrl()).isEqualTo("http://a:8080");
    }

    @Test
    @DisplayName("getByName：未找到返回 null")
    void getByNameNotFound() {
        when(serviceManager.findByName("unknown")).thenReturn(Optional.empty());

        assertThat(registry.getByName("unknown")).isNull();
    }
}