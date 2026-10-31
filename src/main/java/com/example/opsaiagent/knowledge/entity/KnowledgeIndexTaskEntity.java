package com.example.opsaiagent.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识索引任务实体（knowledge_index_task 表）
 * 审核/确认等业务事务只写入任务，分块与向量化由后台 worker 在独立事务中执行。
 */
@Data
@TableName("knowledge_index_task")
public class KnowledgeIndexTaskEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文档 ID */
    private Long docId;

    /** 入队时的文档版本，执行前校验，版本变化说明任务已过期 */
    private Integer docVersion;

    /** 向量 source 标识（如 upload:1 / case:1），用于幂等重建与删除 */
    private String vectorSource;

    /** 任务类型：INDEX */
    private String taskType;

    /** 任务状态：PENDING/RUNNING/SUCCESS/FAILED/SKIPPED */
    private String status;

    /** 已尝试次数 */
    private Integer attempts;

    /** 最大尝试次数 */
    private Integer maxAttempts;

    /** 最近一次失败原因 */
    private String errorMsg;

    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /** 执行租约到期时间，超时未完成的任务由调度回收重试 */
    private LocalDateTime leaseUntil;
}
