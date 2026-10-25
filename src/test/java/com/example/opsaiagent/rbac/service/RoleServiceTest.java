package com.example.opsaiagent.rbac.service;

import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.rbac.entity.PermissionEntity;
import com.example.opsaiagent.rbac.entity.RoleEntity;
import com.example.opsaiagent.rbac.mapper.PermissionMapper;
import com.example.opsaiagent.rbac.mapper.RoleMapper;
import com.example.opsaiagent.rbac.mapper.RolePermissionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RoleService 单元测试。
 *
 * 覆盖：角色/权限列表、角色权限查询与覆盖式更新、非法权限校验。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("角色权限服务")
class RoleServiceTest {

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private PermissionMapper permissionMapper;

    @Mock
    private RolePermissionMapper rolePermissionMapper;

    @Mock
    private PermissionService permissionService;

    @InjectMocks
    private RoleService roleService;

    @Test
    @DisplayName("listRoles：返回角色列表")
    void listRoles() {
        when(roleMapper.selectList(any())).thenReturn(List.of(role(1L, "TENANT_ADMIN")));

        assertThat(roleService.listRoles()).hasSize(1);
    }

    @Test
    @DisplayName("listPermissions：返回权限点列表")
    void listPermissions() {
        when(permissionMapper.selectList(any()))
                .thenReturn(List.of(permission(1L, "user:read")));

        assertThat(roleService.listPermissions()).hasSize(1);
    }

    @Test
    @DisplayName("getPermissionIds：返回角色权限")
    void getPermissionIds() {
        when(roleMapper.selectById(1L)).thenReturn(role(1L, "TENANT_ADMIN"));
        when(rolePermissionMapper.selectPermissionIdsByRoleIds(List.of(1L)))
                .thenReturn(List.of(10L, 11L));

        assertThat(roleService.getPermissionIds(1L)).containsExactly(10L, 11L);
    }

    @Test
    @DisplayName("updatePermissions：覆盖式删除后重新插入并失效缓存")
    void updatePermissions_success() {
        when(roleMapper.selectById(1L)).thenReturn(role(1L, "TENANT_ADMIN"));
        when(permissionMapper.selectBatchIds(any()))
                .thenReturn(List.of(permission(10L, "user:read"), permission(11L, "user:manage")));

        roleService.updatePermissions(1L, List.of(10L, 11L, 10L));

        verify(rolePermissionMapper).deleteByRoleId(1L);
        verify(rolePermissionMapper).insert(1L, 10L);
        verify(rolePermissionMapper).insert(1L, 11L);
        verify(permissionService).evictAll();
    }

    @Test
    @DisplayName("updatePermissions：非法权限 ID 抛业务异常")
    void updatePermissions_invalidPermission() {
        when(roleMapper.selectById(1L)).thenReturn(role(1L, "TENANT_ADMIN"));
        when(permissionMapper.selectBatchIds(any())).thenReturn(List.of());

        assertThatThrownBy(() -> roleService.updatePermissions(1L, List.of(99L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ROLE_PERMISSION_INVALID);
        verify(rolePermissionMapper, never()).deleteByRoleId(anyLong());
    }

    @Test
    @DisplayName("resolveRoleIdsByCodes：按编码解析角色 ID")
    void resolveRoleIdsByCodes() {
        when(roleMapper.selectList(any()))
                .thenReturn(List.of(role(1L, "TENANT_ADMIN"), role(2L, "VIEWER")));

        assertThat(roleService.resolveRoleIdsByCodes(List.of("TENANT_ADMIN", "VIEWER")))
                .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("requireRole：角色不存在抛业务异常")
    void requireRole_notFound() {
        when(roleMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> roleService.requireRole(99L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ROLE_NOT_FOUND);
    }

    private RoleEntity role(Long id, String code) {
        RoleEntity role = new RoleEntity();
        role.setId(id);
        role.setCode(code);
        return role;
    }

    private PermissionEntity permission(Long id, String code) {
        PermissionEntity permission = new PermissionEntity();
        permission.setId(id);
        permission.setCode(code);
        return permission;
    }
}
