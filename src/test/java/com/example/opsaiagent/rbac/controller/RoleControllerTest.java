package com.example.opsaiagent.rbac.controller;

import com.example.opsaiagent.rbac.dto.RolePermissionUpdateRequest;
import com.example.opsaiagent.rbac.entity.PermissionEntity;
import com.example.opsaiagent.rbac.entity.RoleEntity;
import com.example.opsaiagent.rbac.service.RoleService;
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
 * RoleController 单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("角色权限接口")
class RoleControllerTest {

    @Mock
    private RoleService roleService;

    @InjectMocks
    private RoleController controller;

    @Test
    @DisplayName("listRoles：返回角色列表")
    void listRoles() {
        when(roleService.listRoles()).thenReturn(List.of(role()));

        assertThat(controller.listRoles().getData()).hasSize(1);
    }

    @Test
    @DisplayName("listPermissions：返回权限点列表")
    void listPermissions() {
        PermissionEntity permission = new PermissionEntity();
        permission.setId(10L);
        permission.setCode("user:read");
        when(roleService.listPermissions()).thenReturn(List.of(permission));

        assertThat(controller.listPermissions().getData()).hasSize(1);
    }

    @Test
    @DisplayName("updatePermissions：覆盖角色权限")
    void updatePermissions() {
        RolePermissionUpdateRequest request = new RolePermissionUpdateRequest();
        request.setPermissionIds(List.of(10L, 11L));

        controller.updatePermissions(1L, request);

        verify(roleService).updatePermissions(1L, List.of(10L, 11L));
    }

    private RoleEntity role() {
        RoleEntity role = new RoleEntity();
        role.setId(1L);
        role.setCode("TENANT_ADMIN");
        return role;
    }
}
