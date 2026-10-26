package com.example.opsaiagent.rbac.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.rbac.entity.PermissionEntity;
import com.example.opsaiagent.rbac.entity.RoleEntity;
import com.example.opsaiagent.rbac.mapper.PermissionMapper;
import com.example.opsaiagent.rbac.mapper.RoleMapper;
import com.example.opsaiagent.rbac.mapper.RolePermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 角色与权限维护服务。
 *
 * 角色是岗位，权限点是能力；一个角色可绑定多个权限点。
 * 修改角色权限后统一失效用户授权缓存。
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionService permissionService;

    /**
     * 查询全部角色。
     *
     * @return 按 ID 排序的角色列表
     */
    public List<RoleEntity> listRoles() {
        return roleMapper.selectList(new LambdaQueryWrapper<RoleEntity>()
                .orderByAsc(RoleEntity::getId));
    }

    /**
     * 查询全部权限点。
     *
     * @return 按模块、编码排序的权限列表
     */
    public List<PermissionEntity> listPermissions() {
        return permissionMapper.selectList(new LambdaQueryWrapper<PermissionEntity>()
                .orderByAsc(PermissionEntity::getModule)
                .orderByAsc(PermissionEntity::getCode));
    }

    /**
     * 查询角色已绑定的权限 ID。
     *
     * @param roleId 角色 ID
     * @return 权限 ID 列表
     */
    public List<Long> getPermissionIds(Long roleId) {
        requireRole(roleId);
        List<Long> ids = rolePermissionMapper.selectPermissionIdsByRoleIds(List.of(roleId));
        return ids == null ? List.of() : ids;
    }

    /**
     * 覆盖式更新角色权限。
     *
     * @param roleId        角色 ID
     * @param permissionIds 权限 ID 列表；空列表表示清空
     */
    @Transactional
    public void updatePermissions(Long roleId, List<Long> permissionIds) {
        requireRole(roleId);
        LinkedHashSet<Long> distinct = new LinkedHashSet<>();
        if (permissionIds != null) {
            if (permissionIds.stream().anyMatch(java.util.Objects::isNull)) {
                throw new BusinessException(ErrorCode.ROLE_PERMISSION_INVALID, "权限 ID 不能为空");
            }
            distinct.addAll(permissionIds);
        }
        if (!distinct.isEmpty()) {
            List<PermissionEntity> permissions = permissionMapper.selectBatchIds(distinct);
            if (permissions == null || permissions.size() != distinct.size()) {
                throw new BusinessException(ErrorCode.ROLE_PERMISSION_INVALID, "存在不合法的权限 ID");
            }
        }

        rolePermissionMapper.deleteByRoleId(roleId);
        for (Long permissionId : distinct) {
            rolePermissionMapper.insert(roleId, permissionId);
        }
        // 角色权限变化后无法定位受影响用户，整体失效缓存
        permissionService.evictAll();
    }

    /**
     * 按编码查询角色。
     *
     * @param code 角色编码
     * @return 角色；不存在时返回空
     */
    public Optional<RoleEntity> findByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(roleMapper.selectOne(new LambdaQueryWrapper<RoleEntity>()
                .eq(RoleEntity::getCode, code)));
    }

    /**
     * 按编码批量解析角色 ID（用于用户创建/更新）。
     *
     * @param codes 角色编码集合
     * @return 角色 ID 列表；编码不存在的项会被忽略
     */
    public List<Long> resolveRoleIdsByCodes(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        List<RoleEntity> roles = roleMapper.selectList(new LambdaQueryWrapper<RoleEntity>()
                .in(RoleEntity::getCode, codes));
        List<Long> ids = new ArrayList<>();
        for (RoleEntity role : roles) {
            ids.add(role.getId());
        }
        return ids;
    }

    /**
     * 查询角色，不存在时抛业务异常。
     *
     * @param roleId 角色 ID
     * @return 角色
     */
    public RoleEntity requireRole(Long roleId) {
        if (roleId == null) {
            throw new BusinessException(ErrorCode.ROLE_NOT_FOUND, "角色不存在");
        }
        RoleEntity role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException(ErrorCode.ROLE_NOT_FOUND, "角色不存在: " + roleId);
        }
        return role;
    }
}
