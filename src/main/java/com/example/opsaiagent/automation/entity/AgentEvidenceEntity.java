package com.example.opsaiagent.automation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 已脱敏的结构化证据快照；不保存服务认证、URL 或任意原始工具响应。 */
@Data
@TableName("ops_agent_evidence")
public class AgentEvidenceEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private String tenantId;
    private String taskId;
    private String stepId;
    private String evidenceType;
    private String payload;
    private LocalDateTime observedAt;
}
