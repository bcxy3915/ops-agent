package com.example.opsaiagent.rbac.service;

import com.example.opsaiagent.rbac.dto.UserAuthorities;
import com.example.opsaiagent.rbac.entity.PermissionEntity;
import com.example.opsaiagent.rbac.entity.RoleEntity;
import com.example.opsaiagent.rbac.mapper.PermissionMapper;
import com.example.opsaiagent.rbac.mapper.RoleMapper;
import com.example.opsaiagent.rbac.mapper.RolePermissionMapper;
import com.example.opsaiagent.rbac.mapper.UserRoleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PermissionService 单元测试。
 * 覆盖：空用户 / 角色与权限组合 / 无角色 / 无权限 / 缓存命中 / 缓存失效。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("权限服务")
class PermissionServiceTest {

    @Mock
    private UserRoleMapper userRoleMapper;

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private RolePermissionMapper rolePermissionMapper;

    @Mock
    private PermissionMapper permissionMapper;

    @InjectMocks
    private PermissionService permissionService;

    @Test
    @DisplayName("loadAuthorities：用户为空返回空授权，不查库")
    void loadAuthorities_nullUser_returnsEmpty() {
        UserAuthorities result = permissionService.loadAuthorities(null);

        assertThat(result.roles()).isEmpty();
        assertThat(result.permissions()).isEmpty();
        verifyNoInteractions(userRoleMapper, roleMapper, rolePermissionMapper, permissionMapper);
    }

    @Test
    @DisplayName("loadAuthorities：角色与权限点正确合并")
    void loadAuthorities_full() {
        when(userRoleMapper.selectRoleIdsByUserId("u-1")).thenReturn(List.of(1L, 2L));
        when(roleMapper.selectBatchIds(List.of(1L, 2L)))
                .thenReturn(List.of(role(1L, "TENANT_ADMIN"), role(2L, "VIEWER")));
        when(rolePermissionMapper.selectPermissionIdsByRoleIds(List.of(1L, 2L)))
                .thenReturn(List.of(10L, 11L));
        when(permissionMapper.selectBatchIds(List.of(10L, 11L)))
                .thenReturn(List.of(permission(10L, "knowledge:review"), permission(11L, "knowledge:read")));

        UserAuthorities result = permissionService.loadAuthorities("u-1");

        assertThat(result.roles()).containsExactlyInAnyOrder("TENANT_ADMIN", "VIEWER");
        assertThat(result.permissions()).containsExactlyInAnyOrder("knowledge:review", "knowledge:read");
    }

    @Test
    @DisplayName("loadAuthorities：用户无角色返回空授权")
    void loadAuthorities_noRoles() {
        when(userRoleMapper.selectRoleIdsByUserId("u-2")).thenReturn(List.of());

        UserAuthorities result = permissionService.loadAuthorities("u-2");

        assertThat(result.roles()).isEmpty();
        assertThat(result.permissions()).isEmpty();
        verifyNoInteractions(roleMapper, rolePermissionMapper, permissionMapper);
    }

    @Test
    @DisplayName("loadAuthorities：角色存在但无权限点")
    void loadAuthorities_rolesWithoutPermissions() {
        when(userRoleMapper.selectRoleIdsByUserId("u-3")).thenReturn(List.of(3L));
        when(roleMapper.selectBatchIds(List.of(3L))).thenReturn(List.of(role(3L, "VIEWER")));
        when(rolePermissionMapper.selectPermissionIdsByRoleIds(List.of(3L))).thenReturn(List.of());

        UserAuthorities result = permissionService.loadAuthorities("u-3");

        assertThat(result.roles()).containsExactly("VIEWER");
        assertThat(result.permissions()).isEmpty();
    }

    @Test
    @DisplayName("loadAuthorities：同一用户第二次命中缓存")
    void loadAuthorities_cached() {
        when(userRoleMapper.selectRoleIdsByUserId("u-4")).thenReturn(List.of(4L));
        when(roleMapper.selectBatchIds(List.of(4L))).thenReturn(List.of(role(4L, "VIEWER")));
        when(rolePermissionMapper.selectPermissionIdsByRoleIds(List.of(4L))).thenReturn(List.of());

        permissionService.loadAuthorities("u-4");
        permissionService.loadAuthorities("u-4");

        verify(userRoleMapper, times(1)).selectRoleIdsByUserId("u-4");
    }

    @Test
    @DisplayName("evict：缓存失效后重新查库")
    void evict_reloads() {
        when(userRoleMapper.selectRoleIdsByUserId("u-5")).thenReturn(List.of(5L));
        when(roleMapper.selectBatchIds(List.of(5L))).thenReturn(List.of(role(5L, "VIEWER")));
        when(rolePermissionMapper.selectPermissionIdsByRoleIds(List.of(5L))).thenReturn(List.of());

        permissionService.loadAuthorities("u-5");
        permissionService.evict("u-5");
        permissionService.loadAuthorities("u-5");

        verify(userRoleMapper, times(2)).selectRoleIdsByUserId("u-5");
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
