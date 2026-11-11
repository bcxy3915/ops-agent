package com.example.opsaiagent.automation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.automation.entity.AgentTaskEntity;
import org.apache.ibatis.annotations.Mapper;

/** 任务持久化映射，业务查询必须显式限定可见范围。 */
@Mapper
public interface AgentTaskMapper extends BaseMapper<AgentTaskEntity> {
}
