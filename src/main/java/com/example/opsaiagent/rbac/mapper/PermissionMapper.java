package com.example.opsaiagent.rbac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.rbac.entity.PermissionEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 权限点 Mapper。
 */
@Mapper
public interface PermissionMapper extends BaseMapper<PermissionEntity> {
}
