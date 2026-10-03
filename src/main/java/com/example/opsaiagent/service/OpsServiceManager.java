package com.example.opsaiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.entity.OpsServiceEntity;
import com.example.opsaiagent.mapper.OpsServiceMapper;
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
}