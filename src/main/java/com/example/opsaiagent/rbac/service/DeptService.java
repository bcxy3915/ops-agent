package com.example.opsaiagent.rbac.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.rbac.dto.DeptCreateRequest;
import com.example.opsaiagent.rbac.dto.DeptUpdateRequest;
import com.example.opsaiagent.rbac.entity.DeptEntity;
import com.example.opsaiagent.rbac.mapper.DeptMapper;
import com.example.opsaiagent.security.entity.OpsUserEntity;
import com.example.opsaiagent.security.mapper.OpsUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    /** 删除部门前校验是否仍有用户归属 */
    private final OpsUserMapper userMapper;

    /**
     * 查询租户下全部部门（扁平列表，前端按 parentId 组树）。
     *
     * @param tenantId 租户 ID
     * @return 按层级、排序、ID 排列的部门列表
     */
    public List<DeptEntity> listByTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return List.of();
        }
        return deptMapper.selectList(new LambdaQueryWrapper<DeptEntity>()
                .eq(DeptEntity::getTenantId, tenantId)
                .orderByAsc(DeptEntity::getLevel)
                .orderByAsc(DeptEntity::getSort)
                .orderByAsc(DeptEntity::getId));
    }

    /**
     * 创建部门。
     *
     * @param tenantId 租户 ID
     * @param request  创建请求
     * @return 创建后的部门（含物化路径）
     */
    @Transactional
    public DeptEntity create(String tenantId, DeptCreateRequest request) {
        requireTenantId(tenantId);
        String code = request.getCode() == null ? "" : request.getCode().trim();
        if (findByTenantAndCode(tenantId, code).isPresent()) {
            throw new BusinessException(ErrorCode.DEPT_CODE_EXISTS, "部门编码已存在: " + code);
        }

        DeptEntity parent = null;
        int level = 1;
        String parentPath = "/";
        if (request.getParentId() != null) {
            parent = requireDept(tenantId, request.getParentId());
            level = (parent.getLevel() == null ? 1 : parent.getLevel()) + 1;
            parentPath = parent.getPath() == null ? "/" : parent.getPath();
        }

        LocalDateTime now = LocalDateTime.now();
        DeptEntity entity = new DeptEntity();
        entity.setTenantId(tenantId);
        entity.setParentId(request.getParentId());
        entity.setCode(code);
        entity.setName(request.getName() == null ? code : request.getName().trim());
        entity.setPath("/");
        entity.setLevel(level);
        entity.setSort(request.getSort() == null ? 0 : request.getSort());
        entity.setStatus(request.getStatus() == null ? "ACTIVE" : request.getStatus());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        deptMapper.insert(entity);

        // 自增 ID 插入后才知道，补全物化路径：/父路径/当前ID/
        entity.setPath(parentPath + entity.getId() + "/");
        deptMapper.updateById(entity);
        return entity;
    }

    /**
     * 更新部门基础信息（暂不支持修改上级部门）。
     *
     * @param tenantId 租户 ID
     * @param deptId   部门 ID
     * @param request  更新请求
     * @return 更新后的部门
     */
    @Transactional
    public DeptEntity update(String tenantId, Long deptId, DeptUpdateRequest request) {
        DeptEntity entity = requireDept(tenantId, deptId);
        if (request.getName() != null) {
            entity.setName(request.getName().trim());
        }
        if (request.getSort() != null) {
            entity.setSort(request.getSort());
        }
        if (request.getStatus() != null) {
            entity.setStatus(request.getStatus());
        }
        entity.setUpdatedAt(LocalDateTime.now());
        deptMapper.updateById(entity);
        return entity;
    }

    /**
     * 删除部门。
     *
     * <p>存在子部门或仍有关联用户时拒绝删除，避免产生孤儿数据。</p>
     *
     * @param tenantId 租户 ID
     * @param deptId   部门 ID
     */
    @Transactional
    public void delete(String tenantId, Long deptId) {
        requireDept(tenantId, deptId);
        Long children = deptMapper.selectCount(new LambdaQueryWrapper<DeptEntity>()
                .eq(DeptEntity::getParentId, deptId));
        if (children != null && children > 0) {
            throw new BusinessException(ErrorCode.DEPT_HAS_CHILDREN, "部门下存在子部门，不能删除");
        }
        Long users = userMapper.selectCount(new LambdaQueryWrapper<OpsUserEntity>()
                .eq(OpsUserEntity::getDeptId, deptId));
        if (users != null && users > 0) {
            throw new BusinessException(ErrorCode.DEPT_HAS_USERS, "部门下存在用户，不能删除");
        }
        deptMapper.deleteById(deptId);
    }

    /**
     * 按租户 + 编码查询部门。
     *
     * @param tenantId 租户 ID
     * @param code     部门编码
     * @return 部门；不存在时返回空
     */
    public Optional<DeptEntity> findByTenantAndCode(String tenantId, String code) {
        if (tenantId == null || tenantId.isBlank() || code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(deptMapper.selectOne(new LambdaQueryWrapper<DeptEntity>()
                .eq(DeptEntity::getTenantId, tenantId)
                .eq(DeptEntity::getCode, code)));
    }

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

    /**
     * 查询部门并校验租户归属。
     *
     * @param tenantId 租户 ID
     * @param deptId   部门 ID
     * @return 部门
     */
    public DeptEntity requireDept(String tenantId, Long deptId) {
        DeptEntity dept = findById(deptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEPT_NOT_FOUND, "部门不存在: " + deptId));
        if (tenantId == null || !tenantId.equals(dept.getTenantId())) {
            throw new BusinessException(ErrorCode.DEPT_NOT_FOUND, "部门不存在: " + deptId);
        }
        return dept;
    }

    private void requireTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException(ErrorCode.TENANT_REQUIRED, "缺少租户上下文");
        }
    }
}
