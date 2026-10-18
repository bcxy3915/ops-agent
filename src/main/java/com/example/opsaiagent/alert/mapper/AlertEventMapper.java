package com.example.opsaiagent.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.alert.entity.AlertEventEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 告警事件 Mapper
 */
@Mapper
public interface AlertEventMapper extends BaseMapper<AlertEventEntity> {
}
