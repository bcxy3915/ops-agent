package com.example.opsaiagent.rbac.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.rbac.entity.DeptEntity;
import com.example.opsaiagent.rbac.mapper.DeptMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 部门服务。
 *
 * 部门树使用物化路径 {@code path}（如 {@code /1/5/}）：
 * 当前部门及其全部下级的 path 都以当前 path 为前缀，因此可用
 * {@code path LIKE '/1/5/%'} 一次性取出子树，避免递归查询。
 */
@Service
@RequiredArgsConstructor
public class DeptService {

    private final DeptMapper deptMapper;

    /**
     * 按 ID 查询部门。
     *
     * @param deptId 部门 ID
     * @return 部门；不存在时返回空
     */
    public Optional<DeptEntity> findById(Long deptId) {
        if (deptId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(deptMapper.selectById(deptId));
    }

    /**
     * 查询部门子树 ID（包含当前部门）。
     *
     * @param tenantId 租户 ID
     * @param deptId   当前部门 ID
     * @return 部门 ID 列表；租户/部门为空、跨租户或不存在时返回空列表
     */
    public List<Long> subtreeIds(String tenantId, Long deptId) {
        if (tenantId == null || tenantId.isBlank() || deptId == null) {
            return List.of();
        }
        DeptEntity dept = deptMapper.selectById(deptId);
        if (dept == null || !tenantId.equals(dept.getTenantId()) || dept.getPath() == null) {
            return List.of();
        }
        return deptMapper.selectList(new LambdaQueryWrapper<DeptEntity>()
                        .eq(DeptEntity::getTenantId, tenantId)
                        .likeRight(DeptEntity::getPath, dept.getPath()))
                .stream()
                .map(DeptEntity::getId)
                .toList();
    }
}
