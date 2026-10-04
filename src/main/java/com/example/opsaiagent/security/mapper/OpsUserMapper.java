package com.example.opsaiagent.security.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.security.entity.OpsUserEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OpsUserMapper extends BaseMapper<OpsUserEntity> {
}