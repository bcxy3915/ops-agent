package com.example.opsaiagent.rbac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.rbac.entity.RoleEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色 Mapper。
 */
@Mapper
public interface RoleMapper extends BaseMapper<RoleEntity> {
}
