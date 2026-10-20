package com.example.opsaiagent.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.alert.entity.AlertRuleEscalationLevelEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 告警规则升级路径 Mapper
 */
@Mapper
public interface AlertRuleEscalationLevelMapper extends BaseMapper<AlertRuleEscalationLevelEntity> {
}
