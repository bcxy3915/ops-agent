package com.example.opsaiagent.rbac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.rbac.entity.TenantEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 租户 Mapper。
 */
@Mapper
public interface TenantMapper extends BaseMapper<TenantEntity> {
}
