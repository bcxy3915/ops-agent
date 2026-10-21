package com.example.opsaiagent.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.alert.entity.AlertEscalationRecordEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 告警升级事件记录 Mapper
 */
@Mapper
public interface AlertEscalationRecordMapper extends BaseMapper<AlertEscalationRecordEntity> {
}
