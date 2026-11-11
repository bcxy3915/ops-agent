package com.example.opsaiagent.automation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 独立于聊天记忆的任务事实；执行令牌仅在服务端使用。 */
@Data
@TableName("ops_agent_task")
public class AgentTaskEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private String tenantId;
    private Long deptId;
    private String serviceId;
    private String requestTenantId;
    private String requestedBy;
    private String taskType;
    private String status;
    private String outcome;
    private String executionToken;
    private String errorCode;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
