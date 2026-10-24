package com.example.opsaiagent.rbac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.rbac.entity.UserIdentityEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 登录身份 Mapper。
 */
@Mapper
public interface UserIdentityMapper extends BaseMapper<UserIdentityEntity> {
}
