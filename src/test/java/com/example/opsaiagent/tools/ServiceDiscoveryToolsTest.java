package com.example.opsaiagent.tools;

import com.example.opsaiagent.entity.OpsServiceEntity;
import com.example.opsaiagent.service.OpsServiceManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * ServiceDiscoveryTools 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("服务发现工具")
class ServiceDiscoveryToolsTest {

    @Mock
    private OpsServiceManager serviceManager;

    @InjectMocks
    private ServiceDiscoveryTools tools;

    private OpsServiceEntity entity(String name, String baseUrl) {
        OpsServiceEntity e = new OpsServiceEntity();
        e.setName(name);
        e.setBaseUrl(baseUrl);
        return e;
    }

    @Test
    @DisplayName("查询 UP 服务：返回列表")
    void listUpServices() {
        when(serviceManager.findByStatus("UP")).thenReturn(List.of(
                entity("svc-a", "http://a:8080"),
                entity("svc-b", "http://b:8080")
        ));

        String result = tools.listServicesByStatus("UP");

        assertThat(result)
                .contains("共 2 个")
                .contains("svc-a")
                .contains("svc-b")
                .contains("http://a:8080");
    }

    @Test
    @DisplayName("查询 DOWN 服务：返回列表")
    void listDownServices() {
        when(serviceManager.findByStatus("DOWN")).thenReturn(List.of(
                entity("svc-c", "http://c:8080")
        ));

        String result = tools.listServicesByStatus("DOWN");

        assertThat(result).contains("共 1 个").contains("svc-c");
    }

    @Test
    @DisplayName("状态小写：自动转大写")
    void lowercaseStatus() {
        when(serviceManager.findByStatus("UP")).thenReturn(List.of(
                entity("svc-a", "http://a:8080")
        ));

        String result = tools.listServicesByStatus("up");

        assertThat(result).contains("UP").contains("svc-a");
    }

    @Test
    @DisplayName("无匹配服务：返回提示")
    void noMatch() {
        when(serviceManager.findByStatus("UNKNOWN")).thenReturn(List.of());

        String result = tools.listServicesByStatus("UNKNOWN");

        assertThat(result).contains("没有状态为 UNKNOWN 的服务");
    }
}