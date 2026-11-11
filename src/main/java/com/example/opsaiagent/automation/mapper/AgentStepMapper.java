package com.example.opsaiagent.automation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.automation.entity.AgentStepEntity;
import org.apache.ibatis.annotations.Mapper;

/** 任务步骤映射。 */
@Mapper
public interface AgentStepMapper extends BaseMapper<AgentStepEntity> {
}
