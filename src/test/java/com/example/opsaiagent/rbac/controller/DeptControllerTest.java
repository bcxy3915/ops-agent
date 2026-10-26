package com.example.opsaiagent.rbac.controller;

import com.example.opsaiagent.rbac.dto.DeptCreateRequest;
import com.example.opsaiagent.rbac.entity.DeptEntity;
import com.example.opsaiagent.rbac.service.DeptService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeptController 单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("部门管理接口")
class DeptControllerTest {

    @Mock
    private DeptService deptService;

    @InjectMocks
    private DeptController controller;

    @Test
    @DisplayName("list：平台可通过 tenantId 查询部门")
    void list() {
        when(deptService.listByTenant("t1")).thenReturn(List.of(dept(5L)));

        assertThat(controller.list("t1").getData()).hasSize(1);
    }

    @Test
    @DisplayName("create：创建部门")
    void create() {
        DeptCreateRequest request = new DeptCreateRequest();
        request.setCode("OPS");
        request.setName("运维部");
        when(deptService.create("t1", request)).thenReturn(dept(5L));

        assertThat(controller.create(request, "t1").getData().getId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("delete：调用服务删除")
    void delete() {
        controller.delete(5L, "t1");

        verify(deptService).delete("t1", 5L);
    }

    private DeptEntity dept(Long id) {
        DeptEntity dept = new DeptEntity();
        dept.setId(id);
        dept.setTenantId("t1");
        dept.setCode("OPS");
        dept.setName("运维部");
        dept.setPath("/5/");
        dept.setLevel(1);
        return dept;
    }
}
