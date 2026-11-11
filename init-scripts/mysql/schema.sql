-- MySQL 8.4 LTS 全新环境初始化；不要在现有业务库覆盖执行。
-- 来源：当前 PostgreSQL schema.sql；32 张原有业务表及检索投影投递表，不包含向量表。
-- 长文本使用 LONGTEXT，避免 Markdown/诊断内容受 TEXT 64KB 上限截断。
CREATE DATABASE IF NOT EXISTS `opsdb` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
USE `opsdb`;
SET NAMES utf8mb4 COLLATE utf8mb4_0900_bin;
SET time_zone = '+00:00';

CREATE TABLE IF NOT EXISTS `ops_tenant` (
    `id` VARCHAR(32) NOT NULL COMMENT '租户 ID；平台公共数据保留 0',
    `code` VARCHAR(64) NOT NULL COMMENT '企业编码，全局唯一',
    `name` VARCHAR(128) NOT NULL COMMENT '企业名称',
    `status` VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL COMMENT '租户状态：ACTIVE 启用 / SUSPENDED 停用',
    `knowledge_review_mode` VARCHAR(32) DEFAULT 'DEPT_ONLY' NOT NULL COMMENT '知识审核模式：DEPT_ONLY=仅部门；DEPT_AND_TENANT=部门+企业；NONE=免审',
    `review_four_eyes` BOOLEAN DEFAULT TRUE NOT NULL COMMENT '四眼原则开关：TRUE 时审核人不能是上传人',
    `created_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `updated_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    UNIQUE KEY `ops_tenant_code_key` (`code`),
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='企业/租户表；多租户数据隔离的根';

CREATE TABLE IF NOT EXISTS `ops_dept` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `tenant_id` VARCHAR(32) NOT NULL COMMENT '所属租户 ID',
    `parent_id` BIGINT COMMENT '上级部门 ID',
    `code` VARCHAR(64) NOT NULL COMMENT '租户内唯一编码，便于迁移与外部引用',
    `name` VARCHAR(128) NOT NULL COMMENT '名称',
    `path` VARCHAR(512) NOT NULL COMMENT '物化路径（/父ID/子ID/），用于 path LIKE 子树查询',
    `level` INTEGER DEFAULT 1 NOT NULL COMMENT '级别',
    `sort` INTEGER DEFAULT 0 NOT NULL COMMENT '排序',
    `status` VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL COMMENT '状态',
    `created_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `updated_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_dept_path` (`tenant_id`, `path`),
    KEY `idx_dept_tenant` (`tenant_id`),
    UNIQUE KEY `uk_dept_tenant_code` (`tenant_id`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='部门树；支持多级，上级部门的数据范围包含全部下级';

CREATE TABLE IF NOT EXISTS `ops_user` (
    `id` VARCHAR(32) NOT NULL COMMENT '主键 ID',
    `username` VARCHAR(64) NOT NULL COMMENT '用户名',
    `password` VARCHAR(128) NOT NULL COMMENT '密码哈希',
    `role` VARCHAR(32) NOT NULL COMMENT '历史角色字段（ADMIN/OPERATOR/VIEWER），新权限模型以 ops_user_role 为准',
    `enabled` BOOLEAN DEFAULT TRUE COMMENT '是否启用',
    `phone_enc` VARCHAR(256) COMMENT '手机号密文',
    `phone_hash` VARCHAR(64) COMMENT '手机号哈希',
    `email_enc` VARCHAR(256) COMMENT '邮箱密文',
    `email_hash` VARCHAR(64) COMMENT '邮箱哈希',
    `created_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `updated_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    `display_name` VARCHAR(50) COMMENT '显示名称',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户；平台管理员可为 0',
    `dept_id` BIGINT COMMENT '所属部门；平台管理员可为空',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_tenant_username` (`tenant_id`, `username`),
    KEY `idx_ops_user_email_hash` (`email_hash`),
    KEY `idx_ops_user_phone_hash` (`phone_hash`),
    KEY `idx_ops_user_username` (`username`),
    KEY `idx_user_tenant_dept` (`tenant_id`, `dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='用户账号表；保存登录账号、密码哈希和所属租户/部门';

CREATE TABLE IF NOT EXISTS `ops_user_identity` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `user_id` VARCHAR(32) NOT NULL COMMENT '用户 ID',
    `provider` VARCHAR(32) NOT NULL COMMENT '登录方式：LOCAL=邮箱密码；WECHAT/OIDC/SSO 为后续扩展',
    `identifier` VARCHAR(255) NOT NULL COMMENT '登录标识：LOCAL 为邮箱，第三方为 openid/sub',
    `credential_hash` VARCHAR(255) COMMENT '凭证哈希',
    `extra_json` LONGTEXT COMMENT '扩展 JSON',
    `need_bind_email` BOOLEAN DEFAULT FALSE NOT NULL COMMENT '存量迁移账号为 TRUE，首次登录后应绑定真实邮箱',
    `status` VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL COMMENT '状态',
    `last_login_at` DATETIME(6) COMMENT '最近登录时间',
    `created_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_identity_provider_identifier` (`provider`, `identifier`),
    KEY `idx_identity_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='登录身份表；用户与登录方式解耦，新增微信/OIDC 只需增加一行';

CREATE TABLE IF NOT EXISTS `ops_role` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `code` VARCHAR(64) NOT NULL COMMENT '角色编码，全局唯一',
    `name` VARCHAR(64) NOT NULL COMMENT '角色名称',
    `scope` VARCHAR(16) NOT NULL COMMENT '角色作用域：PLATFORM 平台 / TENANT 企业 / DEPT 部门',
    `builtin` BOOLEAN DEFAULT FALSE NOT NULL COMMENT '是否内置角色',
    `created_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    UNIQUE KEY `ops_role_code_key` (`code`),
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='角色表；角色是岗位，权限点才是能力';

CREATE TABLE IF NOT EXISTS `ops_permission` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `code` VARCHAR(128) NOT NULL COMMENT '权限点编码，全局唯一',
    `name` VARCHAR(128) NOT NULL COMMENT '权限点名称',
    `module` VARCHAR(64) COMMENT '所属模块：tenant/org/knowledge/service/alert 等',
    UNIQUE KEY `ops_permission_code_key` (`code`),
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='权限点表；代码只认权限点，不写死角色';

CREATE TABLE IF NOT EXISTS `ops_role_permission` (
    `role_id` BIGINT NOT NULL COMMENT '角色 ID',
    `permission_id` BIGINT NOT NULL COMMENT '权限点 ID',
    PRIMARY KEY (`role_id`, `permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='角色与权限点多对多关系';

CREATE TABLE IF NOT EXISTS `ops_user_role` (
    `user_id` VARCHAR(32) NOT NULL COMMENT '用户 ID',
    `role_id` BIGINT NOT NULL COMMENT '角色 ID',
    PRIMARY KEY (`user_id`, `role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='用户与角色多对多关系；权限取并集';

CREATE TABLE IF NOT EXISTS `ops_service` (
    `id` VARCHAR(32) NOT NULL COMMENT '主键 ID',
    `name` VARCHAR(128) NOT NULL COMMENT '名称',
    `base_url` VARCHAR(255) NOT NULL COMMENT '服务基础地址',
    `health_path` VARCHAR(128) DEFAULT '/actuator/health' COMMENT '健康检查路径',
    `metrics_path` VARCHAR(128) DEFAULT '/actuator/metrics' COMMENT '指标路径',
    `owner` VARCHAR(64) COMMENT '负责人',
    `env` VARCHAR(32) DEFAULT 'dev' COMMENT '环境',
    `tags` JSON COMMENT '标签（JSON）',
    `auth_token` VARCHAR(255) COMMENT '认证令牌',
    `status` VARCHAR(16) DEFAULT 'UNKNOWN' COMMENT '服务健康状态：UP/DOWN/DEGRADED/UNKNOWN',
    `last_checked_at` DATETIME(6) COMMENT '最近检查时间',
    `registered_at` DATETIME(6) COMMENT '注册时间',
    `updated_at` DATETIME(6) COMMENT '更新时间',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户',
    `dept_id` BIGINT COMMENT '归属部门；为空表示待分配',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_service_tenant_name` (`tenant_id`, `name`),
    KEY `idx_ops_service_env` (`env`),
    KEY `idx_ops_service_name` (`name`),
    KEY `idx_ops_service_status` (`status`),
    KEY `idx_service_tenant_dept` (`tenant_id`, `dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='服务注册表；记录被监控服务的地址、环境与健康状态';

CREATE TABLE IF NOT EXISTS `metric_sample` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `service` VARCHAR(128) NOT NULL COMMENT '服务名称',
    `metric` VARCHAR(64) NOT NULL COMMENT '指标名称',
    `value` DOUBLE NOT NULL COMMENT '指标值',
    `sampled_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL COMMENT '采样时间',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户 ID',
    PRIMARY KEY (`id`),
    KEY `idx_metric_sample_lookup` (`service`, `metric`, `sampled_at`),
    KEY `idx_metric_sample_tenant` (`tenant_id`, `sampled_at`),
    KEY `idx_metric_sample_time` (`sampled_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='指标采样表；保存服务指标的时间序列数据';

CREATE TABLE IF NOT EXISTS `alert_rule` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `name` VARCHAR(128) NOT NULL COMMENT '名称',
    `description` VARCHAR(512) COMMENT '描述',
    `service` VARCHAR(128) COMMENT '服务名称',
    `metric` VARCHAR(64) NOT NULL COMMENT '指标名称',
    `operator` VARCHAR(8) NOT NULL COMMENT '比较运算符（> >= < <= == !=）',
    `threshold` DOUBLE NOT NULL COMMENT '阈值',
    `compare_mode` VARCHAR(16) DEFAULT 'THRESHOLD' COMMENT '比较模式',
    `duration_seconds` INTEGER DEFAULT 60 COMMENT '持续秒数',
    `silence_seconds` INTEGER DEFAULT 0 COMMENT '静默秒数',
    `enabled` BOOLEAN DEFAULT TRUE COMMENT '是否启用',
    `created_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `updated_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    `escalation_enabled` BOOLEAN DEFAULT FALSE COMMENT '是否启用升级',
    `escalation_interval_seconds` INTEGER DEFAULT 300 COMMENT '升级间隔秒数',
    `escalation_max_level` INTEGER DEFAULT 2 COMMENT '最大升级级别',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户',
    `dept_id` BIGINT COMMENT '规则归属部门',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_alert_rule_tenant_name` (`tenant_id`, `name`),
    KEY `idx_alert_rule_enabled` (`enabled`),
    KEY `idx_alert_rule_service` (`service`),
    KEY `idx_alert_rule_tenant_dept` (`tenant_id`, `dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='告警规则表；定义指标阈值、持续时间和升级策略';

CREATE TABLE IF NOT EXISTS `alert_rule_escalation_level` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `rule_id` BIGINT NOT NULL COMMENT '告警规则 ID',
    `level_no` INTEGER NOT NULL COMMENT '升级级别序号',
    `after_seconds` INTEGER NOT NULL COMMENT '触发等待秒数',
    `trigger_on` VARCHAR(16) DEFAULT 'UNACKNOWLEDGED' NOT NULL COMMENT '触发条件',
    `to_role` VARCHAR(16) COMMENT '通知角色',
    `to_users` VARCHAR(512) COMMENT '通知用户',
    `channel_ids` VARCHAR(512) COMMENT '通知渠道 ID 列表（逗号分隔）',
    `created_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户 ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rule_level_tenant` (`tenant_id`, `rule_id`, `level_no`),
    KEY `idx_escalation_level_rule` (`rule_id`),
    KEY `idx_escalation_level_tenant` (`tenant_id`, `rule_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='告警规则升级级别表；定义逐级升级条件';

CREATE TABLE IF NOT EXISTS `alert_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `rule_id` BIGINT NOT NULL COMMENT '告警规则 ID',
    `rule_name` VARCHAR(128) COMMENT '告警规则名称',
    `service` VARCHAR(128) COMMENT '服务名称',
    `metric` VARCHAR(64) COMMENT '指标名称',
    `operator` VARCHAR(8) COMMENT '比较运算符（> >= < <= == !=）',
    `threshold` DOUBLE COMMENT '阈值',
    `current_value` DOUBLE COMMENT '当前值',
    `status` VARCHAR(16) NOT NULL COMMENT '告警状态：FIRING/ACKNOWLEDGED/SILENCED/RESOLVED',
    `detail` LONGTEXT COMMENT '详情',
    `fired_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '触发时间',
    `resolved_at` DATETIME(6) COMMENT '恢复时间',
    `updated_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    `acknowledged_by` VARCHAR(64) COMMENT '确认人',
    `silenced_until` DATETIME(6) COMMENT '静默到期时间',
    `silenced_reason` VARCHAR(256) COMMENT '静默原因',
    `escalation_level` INTEGER DEFAULT 0 COMMENT '升级级别',
    `escalated_at` DATETIME(6) COMMENT '升级时间',
    `acknowledged_at` DATETIME(6) COMMENT '确认时间',
    `resolved_by` VARCHAR(64) COMMENT '恢复人',
    `resolution_note` VARCHAR(500) COMMENT '恢复说明',
    `silenced_by` VARCHAR(64) COMMENT '静默人',
    `silenced_from` VARCHAR(16) COMMENT '静默前状态',
    `silenced_note` VARCHAR(500) COMMENT '静默说明',
    `silenced_at` DATETIME(6) COMMENT '静默时间',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户（冗余自规则，便于查询）',
    `dept_id` BIGINT COMMENT '归属部门（冗余自规则，便于查询）',
    PRIMARY KEY (`id`),
    KEY `idx_alert_event_rule` (`rule_id`),
    KEY `idx_alert_event_service` (`service`, `fired_at`),
    KEY `idx_alert_event_status` (`status`, `fired_at`),
    KEY `idx_alert_event_tenant_dept` (`tenant_id`, `dept_id`, `fired_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='告警事件表；记录告警触发、确认、恢复与静默全过程';

CREATE TABLE IF NOT EXISTS `alert_escalation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `alert_event_id` BIGINT NOT NULL COMMENT '告警事件 ID',
    `level` INTEGER NOT NULL COMMENT '级别',
    `trigger_on` VARCHAR(16) COMMENT '触发条件',
    `to_role` VARCHAR(16) COMMENT '通知角色',
    `to_users` VARCHAR(512) COMMENT '通知用户',
    `reason` VARCHAR(256) COMMENT '原因',
    `triggered_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '触发时间',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户 ID',
    `dept_id` BIGINT COMMENT '所属部门 ID',
    PRIMARY KEY (`id`),
    KEY `idx_alert_escalation_event` (`alert_event_id`),
    KEY `idx_alert_escalation_tenant` (`tenant_id`, `alert_event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='告警升级记录表；记录每次升级的级别与通知对象';

CREATE TABLE IF NOT EXISTS `user_alert_read` (
    `user_id` VARCHAR(64) NOT NULL COMMENT '用户 ID',
    `alert_event_id` BIGINT NOT NULL COMMENT '告警事件 ID',
    `read_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '已读时间',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户 ID',
    `dept_id` BIGINT COMMENT '所属部门 ID',
    PRIMARY KEY (`tenant_id`, `user_id`, `alert_event_id`),
    KEY `idx_user_alert_read_tenant` (`tenant_id`, `user_id`),
    KEY `idx_user_alert_read_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='用户告警已读表；记录用户对告警的已读状态';

CREATE TABLE IF NOT EXISTS `notification_channel` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `name` VARCHAR(128) NOT NULL COMMENT '名称',
    `type` VARCHAR(32) NOT NULL COMMENT '类型',
    `enabled` BOOLEAN DEFAULT TRUE COMMENT '是否启用',
    `config` LONGTEXT COMMENT '渠道配置（JSON）',
    `description` VARCHAR(512) COMMENT '描述',
    `created_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `updated_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户 ID',
    `dept_id` BIGINT COMMENT '所属部门 ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_notification_channel_tenant_name` (`tenant_id`, `name`),
    KEY `idx_notification_channel_enabled` (`enabled`),
    KEY `idx_notification_channel_tenant` (`tenant_id`),
    KEY `idx_notification_channel_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='通知渠道表；邮件、飞书、Webhook 等渠道配置';

CREATE TABLE IF NOT EXISTS `notification_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `alert_event_id` BIGINT COMMENT '告警事件 ID',
    `rule_id` BIGINT COMMENT '告警规则 ID',
    `rule_name` VARCHAR(128) COMMENT '告警规则名称',
    `service` VARCHAR(128) COMMENT '服务名称',
    `metric` VARCHAR(64) COMMENT '指标名称',
    `channel_id` BIGINT COMMENT '通知渠道 ID',
    `channel_name` VARCHAR(128) COMMENT '通知渠道名称',
    `channel_type` VARCHAR(32) COMMENT '通知渠道类型',
    `target` VARCHAR(512) COMMENT '操作对象',
    `title` VARCHAR(255) COMMENT '标题',
    `content` LONGTEXT COMMENT '内容',
    `status` VARCHAR(16) NOT NULL COMMENT '发送状态：PENDING/SENT/FAILED',
    `error_message` LONGTEXT COMMENT '错误信息',
    `retry_count` INTEGER DEFAULT 0 COMMENT '重试次数',
    `max_retry` INTEGER DEFAULT 2 COMMENT '最大重试次数',
    `next_retry_at` DATETIME(6) COMMENT '下次重试时间',
    `sent_at` DATETIME(6) COMMENT '发送时间',
    `created_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `updated_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户 ID',
    `dept_id` BIGINT COMMENT '所属部门 ID',
    PRIMARY KEY (`id`),
    KEY `idx_notification_record_alert` (`alert_event_id`),
    KEY `idx_notification_record_channel` (`channel_id`),
    KEY `idx_notification_record_service` (`service`, `created_at`),
    KEY `idx_notification_record_status` (`status`, `next_retry_at`),
    KEY `idx_notification_record_tenant` (`tenant_id`, `dept_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='通知发送记录表；记录通知内容、结果与重试';

CREATE TABLE IF NOT EXISTS `failure_snapshot` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `alert_event_id` BIGINT NOT NULL COMMENT '告警事件 ID',
    `rule_id` BIGINT COMMENT '告警规则 ID',
    `rule_name` VARCHAR(128) COMMENT '告警规则名称',
    `service` VARCHAR(128) COMMENT '服务名称',
    `metric` VARCHAR(64) COMMENT '指标名称',
    `status` VARCHAR(16) COMMENT '状态',
    `snapshot_json` LONGTEXT NOT NULL COMMENT '快照 JSON',
    `created_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户 ID',
    `dept_id` BIGINT COMMENT '所属部门 ID',
    PRIMARY KEY (`id`),
    KEY `idx_failure_snapshot_alert` (`alert_event_id`),
    KEY `idx_failure_snapshot_service` (`service`, `created_at`),
    KEY `idx_failure_snapshot_tenant` (`tenant_id`, `dept_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='故障快照表；保存告警触发时的现场数据';

CREATE TABLE IF NOT EXISTS `diagnosis_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `alert_event_id` BIGINT NOT NULL COMMENT '告警事件 ID',
    `snapshot_id` BIGINT COMMENT '快照 ID',
    `rule_id` BIGINT COMMENT '告警规则 ID',
    `rule_name` VARCHAR(128) COMMENT '告警规则名称',
    `service` VARCHAR(128) COMMENT '服务名称',
    `metric` VARCHAR(64) COMMENT '指标名称',
    `status` VARCHAR(16) NOT NULL COMMENT '状态',
    `root_cause` LONGTEXT COMMENT '根因',
    `evidence` LONGTEXT COMMENT '证据',
    `confidence` INTEGER COMMENT '置信度',
    `suggestions` LONGTEXT COMMENT '处置建议',
    `summary` LONGTEXT COMMENT '摘要',
    `question` VARCHAR(512) COMMENT '问题',
    `answer` LONGTEXT COMMENT '回答内容',
    `error_message` LONGTEXT COMMENT '错误信息',
    `created_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `updated_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    `duration_ms` BIGINT COMMENT '耗时（毫秒）',
    `references_json` LONGTEXT COMMENT '引用 JSON',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户 ID',
    `dept_id` BIGINT COMMENT '所属部门 ID',
    PRIMARY KEY (`id`),
    KEY `idx_diagnosis_alert` (`alert_event_id`),
    KEY `idx_diagnosis_rule` (`rule_id`),
    KEY `idx_diagnosis_service` (`service`, `created_at`),
    KEY `idx_diagnosis_tenant_dept` (`tenant_id`, `dept_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='诊断记录表；保存 AI 诊断的根因、证据与建议';

CREATE TABLE IF NOT EXISTS `maintenance_window` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `service` VARCHAR(128) NOT NULL COMMENT '服务名称',
    `reason` VARCHAR(256) NOT NULL COMMENT '原因',
    `until` DATETIME(6) NOT NULL COMMENT '截止时间',
    `note` VARCHAR(500) COMMENT '备注',
    `created_by` VARCHAR(64) COMMENT '创建人',
    `created_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `ended_at` DATETIME(6) COMMENT '结束时间',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户 ID',
    `dept_id` BIGINT COMMENT '所属部门 ID',
    PRIMARY KEY (`id`),
    KEY `idx_maintenance_tenant` (`tenant_id`, `dept_id`, `until`),
    KEY `idx_maintenance_window_active` (`until`, `ended_at`),
    KEY `idx_maintenance_window_service` (`service`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='维护窗口表；维护期间可抑制相关告警';

CREATE TABLE IF NOT EXISTS `oncall_member` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `user_id` VARCHAR(32) NOT NULL COMMENT '用户 ID',
    `duty_role` VARCHAR(16) NOT NULL COMMENT '值班角色',
    `added_by` VARCHAR(64) COMMENT '添加人',
    `added_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '添加时间',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户 ID',
    `dept_id` BIGINT COMMENT '所属部门 ID',
    PRIMARY KEY (`id`),
    KEY `idx_oncall_member_role` (`duty_role`),
    KEY `idx_oncall_tenant` (`tenant_id`, `dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='值班成员表；记录部门值班人员与值班角色';

CREATE TABLE IF NOT EXISTS `knowledge_document` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `title` VARCHAR(255) NOT NULL COMMENT '标题',
    `doc_type` VARCHAR(16) DEFAULT 'MARKDOWN' NOT NULL COMMENT '文档类型',
    `layer` VARCHAR(16) DEFAULT 'PRIVATE' NOT NULL COMMENT '知识层级',
    `trust_status` VARCHAR(16) DEFAULT 'PENDING' NOT NULL COMMENT '可信状态',
    `version` INTEGER DEFAULT 1 NOT NULL COMMENT '版本号',
    `owner` VARCHAR(64) COMMENT '负责人',
    `service_ids` LONGTEXT COMMENT '关联服务 ID 列表',
    `tags` LONGTEXT COMMENT '标签（JSON）',
    `hit_count` BIGINT DEFAULT 0 COMMENT '命中次数',
    `source` VARCHAR(255) COMMENT '来源',
    `file_path` VARCHAR(512) COMMENT '文件路径',
    `raw_content` LONGTEXT COMMENT '原始内容',
    `chunk_count` INTEGER DEFAULT 0 COMMENT '分块数量',
    `submitted_at` DATETIME(6) COMMENT '提交时间',
    `last_review_at` DATETIME(6) COMMENT '最近审核时间',
    `created_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `updated_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    `effective_at` DATETIME(6) COMMENT '生效时间',
    `review_at` DATETIME(6) COMMENT '审核时间',
    `conflict_status` VARCHAR(16) DEFAULT 'NONE' COMMENT '冲突状态',
    `package_version` VARCHAR(32) COMMENT '知识包版本',
    `released_at` DATETIME(6) COMMENT '发布时间',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户；0 表示平台公共知识',
    `dept_id` BIGINT COMMENT '归属部门；PUBLIC/平台知识可为空',
    `visibility` VARCHAR(32) DEFAULT 'PERSONAL' NOT NULL COMMENT '可见性：PERSONAL/DEPT/TENANT/PUBLIC',
    `review_status` VARCHAR(32) DEFAULT 'DRAFT' NOT NULL COMMENT '审核状态：DRAFT/PENDING_DEPT/PENDING_TENANT/APPROVED/REJECTED',
    `review_level` VARCHAR(16) DEFAULT 'NONE' NOT NULL COMMENT '当前审核层级：NONE/DEPT/TENANT',
    `reviewed_by` VARCHAR(64) COMMENT '最近审核人',
    `reviewed_at` DATETIME(6) COMMENT '最近审核时间',
    `review_note` VARCHAR(500) COMMENT '审核意见/驳回原因',
    `dept_reviewed_by` VARCHAR(64) COMMENT '部门审核人；企业审核后仍保留',
    `dept_reviewed_at` DATETIME(6) COMMENT '部门审核时间；企业审核后仍保留',
    `publish_status` VARCHAR(16) DEFAULT 'UNPUBLISHED' NOT NULL COMMENT '索引发布状态：UNPUBLISHED/BUILDING/FAILED/PUBLISHED',
    `publish_error` LONGTEXT COMMENT '最近一次索引失败原因',
    `published_at` DATETIME(6) COMMENT '索引发布完成时间',
    PRIMARY KEY (`id`),
    KEY `idx_knowledge_doc_layer` (`layer`),
    KEY `idx_knowledge_doc_source` (`source`),
    KEY `idx_knowledge_doc_status` (`trust_status`),
    KEY `idx_knowledge_doc_tenant_dept` (`tenant_id`, `dept_id`),
    KEY `idx_knowledge_doc_title` (`title`),
    KEY `idx_knowledge_doc_visibility` (`tenant_id`, `visibility`, `review_status`),
    KEY `idx_knowledge_doc_publish` (`publish_status`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='知识文档表；保存文档内容、可见性与审核状态';

CREATE TABLE IF NOT EXISTS `knowledge_document_version` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `doc_id` BIGINT NOT NULL COMMENT '文档 ID',
    `version` INTEGER NOT NULL COMMENT '版本号',
    `content_snapshot` LONGTEXT COMMENT '内容快照',
    `changed_by` VARCHAR(64) COMMENT '变更人',
    `change_reason` VARCHAR(255) COMMENT '变更原因',
    `created_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户 ID',
    `dept_id` BIGINT COMMENT '所属部门 ID',
    PRIMARY KEY (`id`),
    KEY `idx_knowledge_version_doc` (`doc_id`, `version`),
    KEY `idx_knowledge_version_tenant` (`tenant_id`, `doc_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='知识文档版本表；保存文档历史版本快照';

CREATE TABLE IF NOT EXISTS `knowledge_chunk` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `doc_id` BIGINT NOT NULL COMMENT '文档 ID',
    `ordinal` INTEGER NOT NULL COMMENT '分块序号',
    `content` LONGTEXT NOT NULL COMMENT '内容',
    `section` VARCHAR(255) COMMENT '章节',
    `vector_id` VARCHAR(64) COMMENT '向量 ID',
    `created_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户 ID',
    `dept_id` BIGINT COMMENT '所属部门 ID',
    PRIMARY KEY (`id`),
    KEY `idx_knowledge_chunk_doc` (`doc_id`, `ordinal`),
    KEY `idx_knowledge_chunk_tenant` (`tenant_id`, `doc_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='知识分块表；保存文档切分后的检索分块';

CREATE TABLE IF NOT EXISTS `knowledge_index_task` (
    `claim_token` VARCHAR(36) COMMENT '本次领取令牌，阻止过期 worker 回写',
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'id',
    `doc_id` BIGINT NOT NULL COMMENT '文档 ID',
    `doc_version` INTEGER DEFAULT 1 NOT NULL COMMENT '入队时的文档版本，用于幂等校验',
    `vector_source` VARCHAR(128) NOT NULL COMMENT '向量 source 标识，用于幂等重建与删除',
    `task_type` VARCHAR(16) DEFAULT 'INDEX' NOT NULL COMMENT '任务类型：INDEX',
    `status` VARCHAR(16) DEFAULT 'PENDING' NOT NULL COMMENT '任务状态：PENDING/RUNNING/SUCCESS/FAILED/SKIPPED',
    `attempts` INTEGER DEFAULT 0 NOT NULL COMMENT '已尝试次数',
    `max_attempts` INTEGER DEFAULT 3 NOT NULL COMMENT '最大尝试次数',
    `error_msg` LONGTEXT COMMENT '最近一次失败原因',
    `created_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `next_retry_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'next_retry_at',
    `started_at` DATETIME(6) COMMENT '开始执行时间',
    `finished_at` DATETIME(6) COMMENT '结束时间',
    `lease_until` DATETIME(6) COMMENT '执行租约到期时间，超时由调度回收',
    PRIMARY KEY (`id`),
    `active_doc_id` BIGINT GENERATED ALWAYS AS (CASE WHEN `status` IN ('PENDING', 'RUNNING') THEN `doc_id` ELSE NULL END) STORED COMMENT '活动任务唯一约束辅助列，不由业务写入',
    UNIQUE KEY `uk_knowledge_index_task_active_doc` (`active_doc_id`),
    KEY `idx_knowledge_index_task_status` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='知识索引任务表；异步分块与向量化';

CREATE TABLE IF NOT EXISTS `knowledge_case` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `source_alert_id` BIGINT COMMENT '来源告警 ID',
    `diagnosis_record_id` BIGINT COMMENT '诊断记录 ID',
    `service` VARCHAR(128) COMMENT '服务名称',
    `title` VARCHAR(255) NOT NULL COMMENT '标题',
    `symptom` LONGTEXT COMMENT '现象',
    `root_cause` LONGTEXT COMMENT '根因',
    `real_root_cause` LONGTEXT COMMENT '真实根因',
    `evidence` LONGTEXT COMMENT '证据',
    `effective_action` LONGTEXT COMMENT '有效处置动作',
    `confidence` INTEGER COMMENT '置信度',
    `masked_status` VARCHAR(16) DEFAULT 'NONE' COMMENT '脱敏状态',
    `status` VARCHAR(16) DEFAULT 'PENDING' NOT NULL COMMENT '案例状态：PENDING/CONFIRMED/REJECTED',
    `duration_min` INTEGER COMMENT '持续分钟数',
    `closed_loop_at` DATETIME(6) COMMENT '闭环时间',
    `confirmed_by` VARCHAR(64) COMMENT '确认人',
    `cited_count` INTEGER DEFAULT 0 COMMENT '被引用次数',
    `created_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `updated_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户',
    `dept_id` BIGINT COMMENT '所属部门 ID',
    `visibility` VARCHAR(32) DEFAULT 'TENANT' NOT NULL COMMENT '案例可见性；确认后默认 TENANT',
    PRIMARY KEY (`id`),
    KEY `idx_knowledge_case_alert` (`source_alert_id`),
    KEY `idx_knowledge_case_diag` (`diagnosis_record_id`),
    KEY `idx_knowledge_case_service` (`service`, `created_at`),
    KEY `idx_knowledge_case_status` (`status`),
    KEY `idx_knowledge_case_tenant` (`tenant_id`, `dept_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='知识案例表；保存故障案例与处置经验';

CREATE TABLE IF NOT EXISTS `knowledge_conflict` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `doc_id` BIGINT NOT NULL COMMENT '文档 ID',
    `matched_doc_id` BIGINT COMMENT '匹配文档 ID',
    `conflict_type` VARCHAR(16) NOT NULL COMMENT '冲突类型',
    `similarity` numeric(5,4) COMMENT '相似度',
    `status` VARCHAR(16) DEFAULT 'PENDING' NOT NULL COMMENT '状态',
    `decision` VARCHAR(16) COMMENT '处理决定',
    `resolved_by` VARCHAR(64) COMMENT '恢复人',
    `created_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `resolved_at` DATETIME(6) COMMENT '恢复时间',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户 ID',
    `dept_id` BIGINT COMMENT '所属部门 ID',
    PRIMARY KEY (`id`),
    KEY `idx_knowledge_conflict_doc` (`doc_id`),
    KEY `idx_knowledge_conflict_status` (`status`),
    KEY `idx_knowledge_conflict_tenant` (`tenant_id`, `dept_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='知识冲突表；记录相似或矛盾的知识文档';

CREATE TABLE IF NOT EXISTS `knowledge_hit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `doc_id` BIGINT NOT NULL COMMENT '文档 ID',
    `query` LONGTEXT COMMENT '查询内容',
    `hit_source` VARCHAR(16) DEFAULT 'ASK' NOT NULL COMMENT '命中来源',
    `channel` VARCHAR(32) COMMENT '渠道',
    `created_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户 ID',
    `dept_id` BIGINT COMMENT '所属部门 ID',
    PRIMARY KEY (`id`),
    KEY `idx_knowledge_hit_doc` (`doc_id`, `created_at`),
    KEY `idx_knowledge_hit_source` (`hit_source`, `created_at`),
    KEY `idx_knowledge_hit_tenant` (`tenant_id`, `dept_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='知识命中日志表；记录检索命中的文档与来源';

CREATE TABLE IF NOT EXISTS `ops_audit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `username` VARCHAR(64) COMMENT '用户名',
    `operation` VARCHAR(64) NOT NULL COMMENT '操作类型',
    `target` VARCHAR(255) COMMENT '操作对象',
    `method` VARCHAR(16) COMMENT 'HTTP 方法',
    `uri` VARCHAR(255) COMMENT '请求 URI',
    `params` LONGTEXT COMMENT '请求参数',
    `result` VARCHAR(16) NOT NULL COMMENT '结果',
    `error_message` LONGTEXT COMMENT '错误信息',
    `ip` VARCHAR(64) COMMENT 'IP 地址',
    `user_agent` VARCHAR(512) COMMENT 'User-Agent',
    `duration_ms` BIGINT COMMENT '耗时（毫秒）',
    `created_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户',
    `dept_id` BIGINT COMMENT '操作人所属部门',
    PRIMARY KEY (`id`),
    KEY `idx_audit_created_at` (`created_at`),
    KEY `idx_audit_operation` (`operation`),
    KEY `idx_audit_result` (`result`),
    KEY `idx_audit_tenant` (`tenant_id`, `dept_id`, `created_at`),
    KEY `idx_audit_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='审计日志表；记录登录、审核、权限变更等操作留痕';

CREATE TABLE IF NOT EXISTS `ops_conversation` (
    `id` VARCHAR(36) NOT NULL COMMENT '主键 ID',
    `session_id` VARCHAR(64) NOT NULL COMMENT '会话 ID',
    `title` VARCHAR(128) COMMENT '会话标题',
    `username` VARCHAR(64) NOT NULL COMMENT '用户名',
    `message_count` INTEGER DEFAULT 0 COMMENT '消息数量',
    `created_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `updated_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    `last_active_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最近活跃时间',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conversation_tenant_session` (`tenant_id`, `session_id`),
    KEY `idx_conv_last_active` (`username`, `last_active_at` DESC),
    KEY `idx_conv_username` (`username`),
    KEY `idx_conversation_tenant` (`tenant_id`, `last_active_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='对话会话表；保存会话元信息与最近活跃时间';

CREATE TABLE IF NOT EXISTS `ops_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `session_id` VARCHAR(64) NOT NULL COMMENT '会话 ID',
    `role` VARCHAR(16) NOT NULL COMMENT '消息角色：user/assistant/tool',
    `content` LONGTEXT NOT NULL COMMENT '内容',
    `reasoning` LONGTEXT COMMENT '推理过程',
    `tool_calls` JSON COMMENT '工具调用（JSON）',
    `tokens_used` INTEGER COMMENT 'Token 数',
    `created_at` DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `tenant_id` VARCHAR(32) DEFAULT 't-default' NOT NULL COMMENT '所属租户（冗余自会话，便于隔离）',
    PRIMARY KEY (`id`),
    KEY `idx_message_tenant` (`tenant_id`, `session_id`, `created_at`),
    KEY `idx_msg_session_id` (`session_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='对话消息表；保存会话中的用户/助手消息';

CREATE TABLE IF NOT EXISTS `ops_chat_turn` (
    `tenant_id` VARCHAR(32) NOT NULL COMMENT 'tenant_id',
    `session_id` VARCHAR(64) NOT NULL COMMENT 'session_id',
    `request_id` VARCHAR(36) NOT NULL COMMENT 'request_id',
    `conversation_id` VARCHAR(36) COMMENT 'conversation_id',
    `expires_at` DATETIME(6) NOT NULL COMMENT 'expires_at',
    PRIMARY KEY (`tenant_id`, `session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='问答轮次租约；跨实例互斥、请求令牌与会话代次校验';

-- 检索投影也是持久化投递表；不在 MySQL 中执行向量检索。
-- 墓碑保留以确保 ES 故障、应用重启和重复投递后仍能补偿删除。
CREATE TABLE IF NOT EXISTS `knowledge_search_projection` (
    `vector_id` VARCHAR(64) NOT NULL COMMENT '稳定 ES 文档 ID；每次分块重建使用新 ID',
    `doc_id` BIGINT NOT NULL COMMENT '权威文档 ID，无物理外键以保留删除墓碑',
    `source` VARCHAR(255) NOT NULL COMMENT '来源标识',
    `content` LONGTEXT NOT NULL COMMENT '检索正文',
    `metadata` JSON NOT NULL COMMENT '权限、来源、分块与切分版本元数据',
    `embedding` JSON COMMENT '投递前临时向量；ES 确认后清空，不在本库执行向量检索',
    `deleted` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '逻辑删除墓碑',
    `sync_status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SYNCED',
    `attempts` INT NOT NULL DEFAULT 0 COMMENT '连续投递失败次数',
    `next_retry_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '重试时间',
    `last_error` VARCHAR(500) COMMENT '最近一次投递异常类型',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    PRIMARY KEY (`vector_id`),
    KEY `idx_projection_pending` (`sync_status`, `next_retry_at`),
    KEY `idx_projection_source` (`source`, `deleted`),
    KEY `idx_projection_doc` (`doc_id`, `deleted`, `sync_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='检索投影持久化投递与删除补偿';

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
