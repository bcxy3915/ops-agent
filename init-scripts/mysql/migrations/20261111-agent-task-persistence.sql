-- MySQL 8.4：P0 任务、步骤与证据持久化；在现有目标业务库执行，不删除或回填历史数据。
-- 全新环境已纳入 schema.sql，无需再单独执行；本脚本仅创建不存在的表。
SET NAMES utf8mb4 COLLATE utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS `ops_agent_task` (
    `id` VARCHAR(32) NOT NULL COMMENT '任务 ID',
    `tenant_id` VARCHAR(32) NOT NULL COMMENT '目标服务所属租户',
    `dept_id` BIGINT NULL COMMENT '创建时目标部门快照',
    `service_id` VARCHAR(32) NOT NULL COMMENT '服务登记 ID，保留历史引用',
    `request_tenant_id` VARCHAR(32) NOT NULL COMMENT '可信发起人租户，平台代企业执行时与目标不同',
    `requested_by` VARCHAR(64) NOT NULL COMMENT '可信发起用户名',
    `task_type` VARCHAR(32) NOT NULL COMMENT '当前为 INSPECTION',
    `status` VARCHAR(32) NOT NULL COMMENT 'RUNNING、SUCCEEDED 或 FAILED；并非服务健康结论',
    `outcome` VARCHAR(32) NULL COMMENT 'HEALTHY、ABNORMAL 或 INSUFFICIENT_EVIDENCE',
    `execution_token` VARCHAR(32) NOT NULL COMMENT '当前执行随机令牌，禁止通过 API 返回',
    `error_code` VARCHAR(64) NULL COMMENT '固定失败码，不存原始异常',
    `created_at` DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    `completed_at` DATETIME(3) NULL COMMENT '完成时间，UTC',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_task_tenant_id` (`tenant_id`, `id`),
    KEY `idx_agent_task_service` (`tenant_id`, `service_id`, `created_at`, `id`),
    KEY `idx_agent_task_scope` (`tenant_id`, `dept_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='运维 Agent 任务事实';

CREATE TABLE IF NOT EXISTS `ops_agent_step` (
    `id` VARCHAR(32) NOT NULL COMMENT '步骤 ID',
    `tenant_id` VARCHAR(32) NOT NULL COMMENT '任务所属租户',
    `task_id` VARCHAR(32) NOT NULL COMMENT '任务 ID',
    `step_no` INT NOT NULL COMMENT '从 1 开始的任务内顺序',
    `skill` VARCHAR(64) NOT NULL COMMENT '受控技能名',
    `status` VARCHAR(32) NOT NULL COMMENT '步骤执行状态',
    `error_code` VARCHAR(64) NULL COMMENT '固定失败码',
    `started_at` DATETIME(3) NOT NULL COMMENT '开始时间，UTC',
    `completed_at` DATETIME(3) NULL COMMENT '完成时间，UTC',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_step_number` (`tenant_id`, `task_id`, `step_no`),
    UNIQUE KEY `uk_agent_step_identity` (`tenant_id`, `task_id`, `id`),
    CONSTRAINT `fk_agent_step_task` FOREIGN KEY (`tenant_id`, `task_id`)
        REFERENCES `ops_agent_task` (`tenant_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='运维 Agent 步骤事实';

CREATE TABLE IF NOT EXISTS `ops_agent_evidence` (
    `id` VARCHAR(32) NOT NULL COMMENT '证据 ID',
    `tenant_id` VARCHAR(32) NOT NULL COMMENT '任务所属租户',
    `task_id` VARCHAR(32) NOT NULL COMMENT '任务 ID',
    `step_id` VARCHAR(32) NOT NULL COMMENT '步骤 ID',
    `evidence_type` VARCHAR(64) NOT NULL COMMENT '当前为 INSPECTION_REPORT',
    `payload` LONGTEXT NOT NULL COMMENT '结构化脱敏报告 JSON，不存服务凭据',
    `observed_at` DATETIME(3) NOT NULL COMMENT '采集完成时间，UTC',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_evidence_step_type` (`tenant_id`, `step_id`, `evidence_type`),
    KEY `idx_agent_evidence_task` (`tenant_id`, `task_id`, `observed_at`, `id`),
    CONSTRAINT `ck_agent_evidence_json` CHECK (JSON_VALID(`payload`)),
    CONSTRAINT `fk_agent_evidence_step` FOREIGN KEY (`tenant_id`, `task_id`, `step_id`)
        REFERENCES `ops_agent_step` (`tenant_id`, `task_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='运维 Agent 脱敏证据快照';
