package com.example.opsaiagent.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.ops.entity.OpsServiceEntity;
import com.example.opsaiagent.ops.mapper.OpsServiceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpsServiceManager {

    private final OpsServiceMapper mapper;

    /**
     * 查询所有服务
     * @return 服务实体
     */
    public List<OpsServiceEntity> listAll() {
        return mapper.selectList(null);
    }

    /**
     * 按名字查询
     * @param name 服务名
     * @return 服务实体
     */
    public Optional<OpsServiceEntity> findByName(String name) {
        return Optional.ofNullable(
                mapper.selectOne(new LambdaQueryWrapper<OpsServiceEntity>()
                        .eq(OpsServiceEntity::getName, name))
        );
    }

    /**
     * 按环境查询
     * @param env 环境
     * @return 服务实体
     */
    public List<OpsServiceEntity> findByEnv(String env) {
        return mapper.selectList(new LambdaQueryWrapper<OpsServiceEntity>()
                .eq(OpsServiceEntity::getEnv, env));
    }

    /**
     * 按状态查询
     * @param status 状态
     * @return 服务实体
     */
    public List<OpsServiceEntity> findByStatus(String status) {
        return mapper.selectList(new LambdaQueryWrapper<OpsServiceEntity>()
                .eq(OpsServiceEntity::getStatus, status));
    }

    /**
     * 按租户 + 部门子树 + 环境/状态查询服务。
     *
     * @param tenantId 租户 ID；为空不限制（平台视角）
     * @param deptIds  可见部门 ID；null=不限制，空列表=无可见部门
     * @param env      环境；为空不限制
     * @param status   状态；为空不限制
     * @return 服务列表
     */
    public List<OpsServiceEntity> listScoped(String tenantId, List<Long> deptIds,
                                             String env, String status) {
        if (deptIds != null && deptIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<OpsServiceEntity> wrapper = new LambdaQueryWrapper<>();
        if (tenantId != null && !tenantId.isBlank()) {
            wrapper.eq(OpsServiceEntity::getTenantId, tenantId);
        }
        if (deptIds != null) {
            wrapper.in(OpsServiceEntity::getDeptId, deptIds);
        }
        if (env != null && !env.isBlank()) {
            wrapper.eq(OpsServiceEntity::getEnv, env);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(OpsServiceEntity::getStatus, status);
        }
        wrapper.orderByAsc(OpsServiceEntity::getName);
        return mapper.selectList(wrapper);
    }

    /**
     * 按租户查询服务。
     *
     * @param tenantId 租户 ID
     * @return 服务列表
     */
    public List<OpsServiceEntity> listByTenant(String tenantId) {
        return listScoped(tenantId, null, null, null);
    }

    /**
     * 按租户 + 服务名查询（租户内服务名唯一）。
     *
     * @param tenantId 租户 ID；为空时退化为全局查询
     * @param name     服务名
     * @return 服务实体
     */
    public Optional<OpsServiceEntity> findByName(String tenantId, String name) {
        LambdaQueryWrapper<OpsServiceEntity> wrapper = new LambdaQueryWrapper<OpsServiceEntity>()
                .eq(OpsServiceEntity::getName, name);
        if (tenantId != null && !tenantId.isBlank()) {
            wrapper.eq(OpsServiceEntity::getTenantId, tenantId);
        }
        return Optional.ofNullable(mapper.selectOne(wrapper));
    }

    /**
     * 按租户 + 服务名删除。
     *
     * @param tenantId 租户 ID；为空时退化为全局删除
     * @param name     服务名
     * @return 是否删除成功
     */
    public boolean deleteByName(String tenantId, String name) {
        LambdaQueryWrapper<OpsServiceEntity> wrapper = new LambdaQueryWrapper<OpsServiceEntity>()
                .eq(OpsServiceEntity::getName, name);
        if (tenantId != null && !tenantId.isBlank()) {
            wrapper.eq(OpsServiceEntity::getTenantId, tenantId);
        }
        return mapper.delete(wrapper) > 0;
    }

    /**
     * 保存或更新（name 已存在则更新）
     * @param entity 服务实体
     */
    public void saveOrUpdate(OpsServiceEntity entity) {
        Optional<OpsServiceEntity> existing = findByName(entity.getName());
        if (existing.isPresent()) {
            entity.setId(existing.get().getId());
            mapper.updateById(entity);
            log.info("更新服务: {}", entity.getName());
        } else {
            mapper.insert(entity);
            log.info("注册服务: {}", entity.getName());
        }
    }

    /**
     * 按名字删除
     * @param name 服务名
     * @return 是否删除成功
     */
    public boolean deleteByName(String name) {
        int rows = mapper.delete(new LambdaQueryWrapper<OpsServiceEntity>()
                .eq(OpsServiceEntity::getName, name));
        return rows > 0;
    }

    /**
     * 更新状态和检查时间
     * @param id 服务ID
     * @param status 状态
     */
    public void updateStatus(String id, String status) {
        OpsServiceEntity entity = new OpsServiceEntity();
        entity.setId(id);
        entity.setStatus(status);
        entity.setLastCheckedAt(java.time.LocalDateTime.now());
        mapper.updateById(entity);
    }

    /**
     * 保存服务
     * @param entity 服务实体
     */
    public void save(OpsServiceEntity entity) {
        mapper.insert(entity);
    }

    /**
     * 更新服务
     * @param entity 服务实体
     */
    public void update(OpsServiceEntity entity) {
        mapper.updateById(entity);
    }
}