package com.example.opsaiagent.automation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 任务步骤事实；首版记录实时巡检步骤，后续诊断按顺序追加。 */
@Data
@TableName("ops_agent_step")
public class AgentStepEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private String tenantId;
    private String taskId;
    private Integer stepNo;
    private String skill;
    private String status;
    private String errorCode;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
