package com.example.opsaiagent.controller;

import com.example.opsaiagent.dto.ApiResponse;
import com.example.opsaiagent.dto.ServiceRegisterRequest;
import com.example.opsaiagent.dto.ServiceResponse;
import com.example.opsaiagent.dto.ServiceUpdateRequest;
import com.example.opsaiagent.entity.OpsServiceEntity;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.service.OpsServiceManager;
import com.example.opsaiagent.service.ServiceHealthChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ServiceController 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("服务管理接口")
class ServiceControllerTest {

    @Mock
    private OpsServiceManager serviceManager;

    @Mock
    private ServiceHealthChecker healthChecker;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ServiceController controller;

    private OpsServiceEntity entity(String name) {
        OpsServiceEntity e = new OpsServiceEntity();
        e.setName(name);
        e.setBaseUrl("http://" + name + ":8080");
        e.setStatus("UP");
        return e;
    }

    // ==================== register ====================

    @Test
    @DisplayName("注册服务：成功")
    void register_success() {
        ServiceRegisterRequest req = new ServiceRegisterRequest();
        req.setName("todo-service");
        req.setBaseUrl("http://todo-service:8080");
        when(serviceManager.findByName("todo-service")).thenReturn(Optional.empty());

        ApiResponse<ServiceResponse> result = controller.register(req);

        assertThat(result.getData().getName()).isEqualTo("todo-service");
        verify(serviceManager).save(any(OpsServiceEntity.class));
    }

    @Test
    @DisplayName("注册服务：已存在抛 BusinessException")
    void register_alreadyExists() {
        ServiceRegisterRequest req = new ServiceRegisterRequest();
        req.setName("todo-service");
        when(serviceManager.findByName("todo-service")).thenReturn(Optional.of(entity("todo-service")));

        assertThatThrownBy(() -> controller.register(req))
                .isInstanceOf(BusinessException.class);
    }

    // ==================== list ====================

    @Test
    @DisplayName("列表查询：无过滤返回全部")
    void list_all() {
        when(serviceManager.listAll()).thenReturn(List.of(entity("svc-a"), entity("svc-b")));

        ApiResponse<List<ServiceResponse>> result = controller.list(null, null);

        assertThat(result.getData()).hasSize(2);
    }

    @Test
    @DisplayName("列表查询：按 env 过滤")
    void list_byEnv() {
        when(serviceManager.findByEnv("prod")).thenReturn(List.of(entity("svc-a")));

        ApiResponse<List<ServiceResponse>> result = controller.list("prod", null);

        assertThat(result.getData()).hasSize(1);
    }

    @Test
    @DisplayName("列表查询：按 status 过滤")
    void list_byStatus() {
        when(serviceManager.findByStatus("UP")).thenReturn(List.of(entity("svc-a")));

        ApiResponse<List<ServiceResponse>> result = controller.list(null, "UP");

        assertThat(result.getData()).hasSize(1);
    }

    // ==================== get ====================

    @Test
    @DisplayName("查询单个服务：成功")
    void get_success() {
        when(serviceManager.findByName("svc-a")).thenReturn(Optional.of(entity("svc-a")));

        ApiResponse<ServiceResponse> result = controller.get("svc-a");

        assertThat(result.getData().getName()).isEqualTo("svc-a");
    }

    @Test
    @DisplayName("查询单个服务：不存在抛 BusinessException")
    void get_notFound() {
        when(serviceManager.findByName("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.get("unknown"))
                .isInstanceOf(BusinessException.class);
    }

    // ==================== update ====================

    @Test
    @DisplayName("更新服务：成功")
    void update_success() {
        OpsServiceEntity existing = entity("svc-a");
        when(serviceManager.findByName("svc-a")).thenReturn(Optional.of(existing));

        ServiceUpdateRequest req = new ServiceUpdateRequest();
        req.setBaseUrl("http://new-url:8080");
        req.setOwner("new-owner");

        ApiResponse<ServiceResponse> result = controller.update("svc-a", req);

        assertThat(result.getData()).isNotNull();
        verify(serviceManager).update(existing);
    }

    @Test
    @DisplayName("更新服务：不存在抛 BusinessException")
    void update_notFound() {
        when(serviceManager.findByName("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.update("unknown", new ServiceUpdateRequest()))
                .isInstanceOf(BusinessException.class);
    }

    // ==================== delete ====================

    @Test
    @DisplayName("删除服务：成功")
    void delete_success() {
        when(serviceManager.deleteByName("svc-a")).thenReturn(true);

        ApiResponse<Map<String, String>> result = controller.delete("svc-a");

        assertThat(result.getData()).containsEntry("deleted", "svc-a");
    }

    @Test
    @DisplayName("删除服务：不存在抛 BusinessException")
    void delete_notFound() {
        when(serviceManager.deleteByName("unknown")).thenReturn(false);

        assertThatThrownBy(() -> controller.delete("unknown"))
                .isInstanceOf(BusinessException.class);
    }

    // ==================== check ====================

    @Test
    @DisplayName("健康检查：返回状态")
    void check() {
        when(healthChecker.checkNow("svc-a")).thenReturn("UP");

        ApiResponse<Map<String, Object>> result = controller.check("svc-a");

        assertThat(result.getData())
                .containsEntry("name", "svc-a")
                .containsEntry("status", "UP");
    }
}