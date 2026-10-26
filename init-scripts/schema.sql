-- ============================================================
-- Ops Agent 数据库表结构（最终态）
-- 适用：PostgreSQL 16 + pgvector
-- 说明：
--   1. 已合并历史 ALTER TABLE 变更，直接以最终结构建表；
--   2. 所有表、所有字段均带注释；
--   3. 初始化顺序：先执行 schema.sql，再执行 seed.sql；
--   4. 本脚本可重复执行（IF NOT EXISTS / ON CONFLICT 由 seed 保证）。
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;

SET search_path TO public;

-- ------------------------------------------------------------
-- ops_tenant：企业/租户表；多租户数据隔离的根
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_tenant (
    id VARCHAR(32) NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL,
    knowledge_review_mode VARCHAR(32) DEFAULT 'DEPT_ONLY' NOT NULL,
    review_four_eyes BOOLEAN DEFAULT TRUE NOT NULL,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    CONSTRAINT ops_tenant_code_key UNIQUE (code),
    CONSTRAINT ops_tenant_pkey PRIMARY KEY (id)
);

COMMENT ON TABLE ops_tenant IS '企业/租户表；多租户数据隔离的根';
COMMENT ON COLUMN ops_tenant.id IS '租户 ID；平台公共数据保留 0';
COMMENT ON COLUMN ops_tenant.code IS '企业编码，全局唯一';
COMMENT ON COLUMN ops_tenant.name IS '企业名称';
COMMENT ON COLUMN ops_tenant.status IS '租户状态：ACTIVE 启用 / SUSPENDED 停用';
COMMENT ON COLUMN ops_tenant.knowledge_review_mode IS '知识审核模式：DEPT_ONLY=仅部门；DEPT_AND_TENANT=部门+企业；NONE=免审';
COMMENT ON COLUMN ops_tenant.review_four_eyes IS '四眼原则开关：TRUE 时审核人不能是上传人';
COMMENT ON COLUMN ops_tenant.created_at IS '创建时间';
COMMENT ON COLUMN ops_tenant.updated_at IS '更新时间';

-- ------------------------------------------------------------
-- ops_dept：部门树；支持多级，上级部门的数据范围包含全部下级
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_dept (
    id BIGSERIAL,
    tenant_id VARCHAR(32) NOT NULL,
    parent_id BIGINT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    path VARCHAR(512) NOT NULL,
    level INTEGER DEFAULT 1 NOT NULL,
    sort INTEGER DEFAULT 0 NOT NULL,
    status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    CONSTRAINT ops_dept_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_dept_path ON ops_dept USING btree (tenant_id, path);
CREATE INDEX IF NOT EXISTS idx_dept_tenant ON ops_dept USING btree (tenant_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_dept_tenant_code ON ops_dept USING btree (tenant_id, code);

COMMENT ON TABLE ops_dept IS '部门树；支持多级，上级部门的数据范围包含全部下级';
COMMENT ON COLUMN ops_dept.id IS '主键 ID';
COMMENT ON COLUMN ops_dept.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN ops_dept.parent_id IS '上级部门 ID';
COMMENT ON COLUMN ops_dept.code IS '租户内唯一编码，便于迁移与外部引用';
COMMENT ON COLUMN ops_dept.name IS '名称';
COMMENT ON COLUMN ops_dept.path IS '物化路径（/父ID/子ID/），用于 path LIKE 子树查询';
COMMENT ON COLUMN ops_dept.level IS '级别';
COMMENT ON COLUMN ops_dept.sort IS '排序';
COMMENT ON COLUMN ops_dept.status IS '状态';
COMMENT ON COLUMN ops_dept.created_at IS '创建时间';
COMMENT ON COLUMN ops_dept.updated_at IS '更新时间';

-- ------------------------------------------------------------
-- ops_user：用户账号表；保存登录账号、密码哈希和所属租户/部门
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_user (
    id VARCHAR(32) NOT NULL,
    username VARCHAR(64) NOT NULL,
    password VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL,
    enabled BOOLEAN DEFAULT TRUE,
    phone_enc VARCHAR(256),
    phone_hash VARCHAR(64),
    email_enc VARCHAR(256),
    email_hash VARCHAR(64),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    display_name VARCHAR(50),
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    dept_id BIGINT,
    CONSTRAINT ops_user_pkey PRIMARY KEY (id),
    CONSTRAINT uk_user_tenant_username UNIQUE (tenant_id, username)
);

CREATE INDEX IF NOT EXISTS idx_ops_user_email_hash ON ops_user USING btree (email_hash) WHERE (email_hash IS NOT NULL);
CREATE INDEX IF NOT EXISTS idx_ops_user_phone_hash ON ops_user USING btree (phone_hash) WHERE (phone_hash IS NOT NULL);
CREATE INDEX IF NOT EXISTS idx_ops_user_username ON ops_user USING btree (username);
CREATE INDEX IF NOT EXISTS idx_user_tenant_dept ON ops_user USING btree (tenant_id, dept_id);

COMMENT ON TABLE ops_user IS '用户账号表；保存登录账号、密码哈希和所属租户/部门';
COMMENT ON COLUMN ops_user.id IS '主键 ID';
COMMENT ON COLUMN ops_user.username IS '用户名';
COMMENT ON COLUMN ops_user.password IS '密码哈希';
COMMENT ON COLUMN ops_user.role IS '历史角色字段（ADMIN/OPERATOR/VIEWER），新权限模型以 ops_user_role 为准';
COMMENT ON COLUMN ops_user.enabled IS '是否启用';
COMMENT ON COLUMN ops_user.phone_enc IS '手机号密文';
COMMENT ON COLUMN ops_user.phone_hash IS '手机号哈希';
COMMENT ON COLUMN ops_user.email_enc IS '邮箱密文';
COMMENT ON COLUMN ops_user.email_hash IS '邮箱哈希';
COMMENT ON COLUMN ops_user.created_at IS '创建时间';
COMMENT ON COLUMN ops_user.updated_at IS '更新时间';
COMMENT ON COLUMN ops_user.display_name IS '显示名称';
COMMENT ON COLUMN ops_user.tenant_id IS '所属租户；平台管理员可为 0';
COMMENT ON COLUMN ops_user.dept_id IS '所属部门；平台管理员可为空';

-- ------------------------------------------------------------
-- ops_user_identity：登录身份表；用户与登录方式解耦，新增微信/OIDC 只需增加一行
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_user_identity (
    id BIGSERIAL,
    user_id VARCHAR(32) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    identifier VARCHAR(255) NOT NULL,
    credential_hash VARCHAR(255),
    extra_json text,
    need_bind_email BOOLEAN DEFAULT FALSE NOT NULL,
    status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL,
    last_login_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    CONSTRAINT ops_user_identity_pkey PRIMARY KEY (id),
    CONSTRAINT uk_identity_provider_identifier UNIQUE (provider, identifier)
);

CREATE INDEX IF NOT EXISTS idx_identity_user ON ops_user_identity USING btree (user_id);

COMMENT ON TABLE ops_user_identity IS '登录身份表；用户与登录方式解耦，新增微信/OIDC 只需增加一行';
COMMENT ON COLUMN ops_user_identity.id IS '主键 ID';
COMMENT ON COLUMN ops_user_identity.user_id IS '用户 ID';
COMMENT ON COLUMN ops_user_identity.provider IS '登录方式：LOCAL=邮箱密码；WECHAT/OIDC/SSO 为后续扩展';
COMMENT ON COLUMN ops_user_identity.identifier IS '登录标识：LOCAL 为邮箱，第三方为 openid/sub';
COMMENT ON COLUMN ops_user_identity.credential_hash IS '凭证哈希';
COMMENT ON COLUMN ops_user_identity.extra_json IS '扩展 JSON';
COMMENT ON COLUMN ops_user_identity.need_bind_email IS '存量迁移账号为 TRUE，首次登录后应绑定真实邮箱';
COMMENT ON COLUMN ops_user_identity.status IS '状态';
COMMENT ON COLUMN ops_user_identity.last_login_at IS '最近登录时间';
COMMENT ON COLUMN ops_user_identity.created_at IS '创建时间';

-- ------------------------------------------------------------
-- ops_role：角色表；角色是岗位，权限点才是能力
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_role (
    id BIGSERIAL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    scope VARCHAR(16) NOT NULL,
    builtin BOOLEAN DEFAULT FALSE NOT NULL,
    created_at TIMESTAMP DEFAULT NOW(),
    CONSTRAINT ops_role_code_key UNIQUE (code),
    CONSTRAINT ops_role_pkey PRIMARY KEY (id)
);

COMMENT ON TABLE ops_role IS '角色表；角色是岗位，权限点才是能力';
COMMENT ON COLUMN ops_role.id IS '主键 ID';
COMMENT ON COLUMN ops_role.code IS '角色编码，全局唯一';
COMMENT ON COLUMN ops_role.name IS '角色名称';
COMMENT ON COLUMN ops_role.scope IS '角色作用域：PLATFORM 平台 / TENANT 企业 / DEPT 部门';
COMMENT ON COLUMN ops_role.builtin IS '是否内置角色';
COMMENT ON COLUMN ops_role.created_at IS '创建时间';

-- ------------------------------------------------------------
-- ops_permission：权限点表；代码只认权限点，不写死角色
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_permission (
    id BIGSERIAL,
    code VARCHAR(128) NOT NULL,
    name VARCHAR(128) NOT NULL,
    module VARCHAR(64),
    CONSTRAINT ops_permission_code_key UNIQUE (code),
    CONSTRAINT ops_permission_pkey PRIMARY KEY (id)
);

COMMENT ON TABLE ops_permission IS '权限点表；代码只认权限点，不写死角色';
COMMENT ON COLUMN ops_permission.id IS '主键 ID';
COMMENT ON COLUMN ops_permission.code IS '权限点编码，全局唯一';
COMMENT ON COLUMN ops_permission.name IS '权限点名称';
COMMENT ON COLUMN ops_permission.module IS '所属模块：tenant/org/knowledge/service/alert 等';

-- ------------------------------------------------------------
-- ops_role_permission：角色与权限点多对多关系
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_role_permission (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    CONSTRAINT ops_role_permission_pkey PRIMARY KEY (role_id, permission_id)
);

COMMENT ON TABLE ops_role_permission IS '角色与权限点多对多关系';
COMMENT ON COLUMN ops_role_permission.role_id IS '角色 ID';
COMMENT ON COLUMN ops_role_permission.permission_id IS '权限点 ID';

-- ------------------------------------------------------------
-- ops_user_role：用户与角色多对多关系；权限取并集
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_user_role (
    user_id VARCHAR(32) NOT NULL,
    role_id BIGINT NOT NULL,
    CONSTRAINT ops_user_role_pkey PRIMARY KEY (user_id, role_id)
);

COMMENT ON TABLE ops_user_role IS '用户与角色多对多关系；权限取并集';
COMMENT ON COLUMN ops_user_role.user_id IS '用户 ID';
COMMENT ON COLUMN ops_user_role.role_id IS '角色 ID';

-- ------------------------------------------------------------
-- ops_service：服务注册表；记录被监控服务的地址、环境与健康状态
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_service (
    id VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    base_url VARCHAR(255) NOT NULL,
    health_path VARCHAR(128) DEFAULT '/actuator/health',
    metrics_path VARCHAR(128) DEFAULT '/actuator/metrics',
    owner VARCHAR(64),
    env VARCHAR(32) DEFAULT 'dev',
    tags jsonb,
    auth_token VARCHAR(255),
    status VARCHAR(16) DEFAULT 'UNKNOWN',
    last_checked_at TIMESTAMP,
    registered_at TIMESTAMP,
    updated_at TIMESTAMP,
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    dept_id BIGINT,
    CONSTRAINT ops_service_pkey PRIMARY KEY (id),
    CONSTRAINT uk_service_tenant_name UNIQUE (tenant_id, name)
);

CREATE INDEX IF NOT EXISTS idx_ops_service_env ON ops_service USING btree (env);
CREATE INDEX IF NOT EXISTS idx_ops_service_name ON ops_service USING btree (name);
CREATE INDEX IF NOT EXISTS idx_ops_service_status ON ops_service USING btree (status);
CREATE INDEX IF NOT EXISTS idx_service_tenant_dept ON ops_service USING btree (tenant_id, dept_id);

COMMENT ON TABLE ops_service IS '服务注册表；记录被监控服务的地址、环境与健康状态';
COMMENT ON COLUMN ops_service.id IS '主键 ID';
COMMENT ON COLUMN ops_service.name IS '名称';
COMMENT ON COLUMN ops_service.base_url IS '服务基础地址';
COMMENT ON COLUMN ops_service.health_path IS '健康检查路径';
COMMENT ON COLUMN ops_service.metrics_path IS '指标路径';
COMMENT ON COLUMN ops_service.owner IS '负责人';
COMMENT ON COLUMN ops_service.env IS '环境';
COMMENT ON COLUMN ops_service.tags IS '标签（JSON）';
COMMENT ON COLUMN ops_service.auth_token IS '认证令牌';
COMMENT ON COLUMN ops_service.status IS '服务健康状态：UP/DOWN/DEGRADED/UNKNOWN';
COMMENT ON COLUMN ops_service.last_checked_at IS '最近检查时间';
COMMENT ON COLUMN ops_service.registered_at IS '注册时间';
COMMENT ON COLUMN ops_service.updated_at IS '更新时间';
COMMENT ON COLUMN ops_service.tenant_id IS '所属租户';
COMMENT ON COLUMN ops_service.dept_id IS '归属部门；为空表示待分配';

-- ------------------------------------------------------------
-- metric_sample：指标采样表；保存服务指标的时间序列数据
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS metric_sample (
    id BIGSERIAL,
    service VARCHAR(128) NOT NULL,
    metric VARCHAR(64) NOT NULL,
    value DOUBLE PRECISION NOT NULL,
    sampled_at TIMESTAMP DEFAULT NOW() NOT NULL,
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    CONSTRAINT metric_sample_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_metric_sample_lookup ON metric_sample USING btree (service, metric, sampled_at);
CREATE INDEX IF NOT EXISTS idx_metric_sample_tenant ON metric_sample USING btree (tenant_id, sampled_at);
CREATE INDEX IF NOT EXISTS idx_metric_sample_time ON metric_sample USING btree (sampled_at);

COMMENT ON TABLE metric_sample IS '指标采样表；保存服务指标的时间序列数据';
COMMENT ON COLUMN metric_sample.id IS '主键 ID';
COMMENT ON COLUMN metric_sample.service IS '服务名称';
COMMENT ON COLUMN metric_sample.metric IS '指标名称';
COMMENT ON COLUMN metric_sample.value IS '指标值';
COMMENT ON COLUMN metric_sample.sampled_at IS '采样时间';
COMMENT ON COLUMN metric_sample.tenant_id IS '所属租户 ID';

-- ------------------------------------------------------------
-- alert_rule：告警规则表；定义指标阈值、持续时间和升级策略
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS alert_rule (
    id BIGSERIAL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    service VARCHAR(128),
    metric VARCHAR(64) NOT NULL,
    operator VARCHAR(8) NOT NULL,
    threshold DOUBLE PRECISION NOT NULL,
    compare_mode VARCHAR(16) DEFAULT 'THRESHOLD',
    duration_seconds INTEGER DEFAULT 60,
    silence_seconds INTEGER DEFAULT 0,
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    escalation_enabled BOOLEAN DEFAULT FALSE,
    escalation_interval_seconds INTEGER DEFAULT 300,
    escalation_max_level INTEGER DEFAULT 2,
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    dept_id BIGINT,
    CONSTRAINT alert_rule_pkey PRIMARY KEY (id),
    CONSTRAINT uk_alert_rule_tenant_name UNIQUE (tenant_id, name)
);

CREATE INDEX IF NOT EXISTS idx_alert_rule_enabled ON alert_rule USING btree (enabled);
CREATE INDEX IF NOT EXISTS idx_alert_rule_service ON alert_rule USING btree (service);
CREATE INDEX IF NOT EXISTS idx_alert_rule_tenant_dept ON alert_rule USING btree (tenant_id, dept_id);

COMMENT ON TABLE alert_rule IS '告警规则表；定义指标阈值、持续时间和升级策略';
COMMENT ON COLUMN alert_rule.id IS '主键 ID';
COMMENT ON COLUMN alert_rule.name IS '名称';
COMMENT ON COLUMN alert_rule.description IS '描述';
COMMENT ON COLUMN alert_rule.service IS '服务名称';
COMMENT ON COLUMN alert_rule.metric IS '指标名称';
COMMENT ON COLUMN alert_rule.operator IS '比较运算符（> >= < <= == !=）';
COMMENT ON COLUMN alert_rule.threshold IS '阈值';
COMMENT ON COLUMN alert_rule.compare_mode IS '比较模式';
COMMENT ON COLUMN alert_rule.duration_seconds IS '持续秒数';
COMMENT ON COLUMN alert_rule.silence_seconds IS '静默秒数';
COMMENT ON COLUMN alert_rule.enabled IS '是否启用';
COMMENT ON COLUMN alert_rule.created_at IS '创建时间';
COMMENT ON COLUMN alert_rule.updated_at IS '更新时间';
COMMENT ON COLUMN alert_rule.escalation_enabled IS '是否启用升级';
COMMENT ON COLUMN alert_rule.escalation_interval_seconds IS '升级间隔秒数';
COMMENT ON COLUMN alert_rule.escalation_max_level IS '最大升级级别';
COMMENT ON COLUMN alert_rule.tenant_id IS '所属租户';
COMMENT ON COLUMN alert_rule.dept_id IS '规则归属部门';

-- ------------------------------------------------------------
-- alert_rule_escalation_level：告警规则升级级别表；定义逐级升级条件
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS alert_rule_escalation_level (
    id BIGSERIAL,
    rule_id BIGINT NOT NULL,
    level_no INTEGER NOT NULL,
    after_seconds INTEGER NOT NULL,
    trigger_on VARCHAR(16) DEFAULT 'UNACKNOWLEDGED' NOT NULL,
    to_role VARCHAR(16),
    to_users VARCHAR(512),
    channel_ids VARCHAR(512),
    created_at TIMESTAMP DEFAULT NOW(),
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    CONSTRAINT alert_rule_escalation_level_pkey PRIMARY KEY (id),
    CONSTRAINT uk_rule_level_tenant UNIQUE (tenant_id, rule_id, level_no)
);

CREATE INDEX IF NOT EXISTS idx_escalation_level_rule ON alert_rule_escalation_level USING btree (rule_id);
CREATE INDEX IF NOT EXISTS idx_escalation_level_tenant ON alert_rule_escalation_level USING btree (tenant_id, rule_id);

COMMENT ON TABLE alert_rule_escalation_level IS '告警规则升级级别表；定义逐级升级条件';
COMMENT ON COLUMN alert_rule_escalation_level.id IS '主键 ID';
COMMENT ON COLUMN alert_rule_escalation_level.rule_id IS '告警规则 ID';
COMMENT ON COLUMN alert_rule_escalation_level.level_no IS '升级级别序号';
COMMENT ON COLUMN alert_rule_escalation_level.after_seconds IS '触发等待秒数';
COMMENT ON COLUMN alert_rule_escalation_level.trigger_on IS '触发条件';
COMMENT ON COLUMN alert_rule_escalation_level.to_role IS '通知角色';
COMMENT ON COLUMN alert_rule_escalation_level.to_users IS '通知用户';
COMMENT ON COLUMN alert_rule_escalation_level.channel_ids IS '通知渠道 ID 列表（逗号分隔）';
COMMENT ON COLUMN alert_rule_escalation_level.created_at IS '创建时间';
COMMENT ON COLUMN alert_rule_escalation_level.tenant_id IS '所属租户 ID';

-- ------------------------------------------------------------
-- alert_event：告警事件表；记录告警触发、确认、恢复与静默全过程
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS alert_event (
    id BIGSERIAL,
    rule_id BIGINT NOT NULL,
    rule_name VARCHAR(128),
    service VARCHAR(128),
    metric VARCHAR(64),
    operator VARCHAR(8),
    threshold DOUBLE PRECISION,
    current_value DOUBLE PRECISION,
    status VARCHAR(16) NOT NULL,
    detail text,
    fired_at TIMESTAMP DEFAULT NOW(),
    resolved_at TIMESTAMP,
    updated_at TIMESTAMP DEFAULT NOW(),
    acknowledged_by VARCHAR(64),
    silenced_until TIMESTAMP,
    silenced_reason VARCHAR(256),
    escalation_level INTEGER DEFAULT 0,
    escalated_at TIMESTAMP,
    acknowledged_at TIMESTAMP,
    resolved_by VARCHAR(64),
    resolution_note VARCHAR(500),
    silenced_by VARCHAR(64),
    silenced_from VARCHAR(16),
    silenced_note VARCHAR(500),
    silenced_at TIMESTAMP,
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    dept_id BIGINT,
    CONSTRAINT alert_event_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_alert_event_rule ON alert_event USING btree (rule_id);
CREATE INDEX IF NOT EXISTS idx_alert_event_service ON alert_event USING btree (service, fired_at);
CREATE INDEX IF NOT EXISTS idx_alert_event_status ON alert_event USING btree (status, fired_at);
CREATE INDEX IF NOT EXISTS idx_alert_event_tenant_dept ON alert_event USING btree (tenant_id, dept_id, fired_at);

COMMENT ON TABLE alert_event IS '告警事件表；记录告警触发、确认、恢复与静默全过程';
COMMENT ON COLUMN alert_event.id IS '主键 ID';
COMMENT ON COLUMN alert_event.rule_id IS '告警规则 ID';
COMMENT ON COLUMN alert_event.rule_name IS '告警规则名称';
COMMENT ON COLUMN alert_event.service IS '服务名称';
COMMENT ON COLUMN alert_event.metric IS '指标名称';
COMMENT ON COLUMN alert_event.operator IS '比较运算符（> >= < <= == !=）';
COMMENT ON COLUMN alert_event.threshold IS '阈值';
COMMENT ON COLUMN alert_event.current_value IS '当前值';
COMMENT ON COLUMN alert_event.status IS '告警状态：FIRING/ACKNOWLEDGED/SILENCED/RESOLVED';
COMMENT ON COLUMN alert_event.detail IS '详情';
COMMENT ON COLUMN alert_event.fired_at IS '触发时间';
COMMENT ON COLUMN alert_event.resolved_at IS '恢复时间';
COMMENT ON COLUMN alert_event.updated_at IS '更新时间';
COMMENT ON COLUMN alert_event.acknowledged_by IS '确认人';
COMMENT ON COLUMN alert_event.silenced_until IS '静默到期时间';
COMMENT ON COLUMN alert_event.silenced_reason IS '静默原因';
COMMENT ON COLUMN alert_event.escalation_level IS '升级级别';
COMMENT ON COLUMN alert_event.escalated_at IS '升级时间';
COMMENT ON COLUMN alert_event.acknowledged_at IS '确认时间';
COMMENT ON COLUMN alert_event.resolved_by IS '恢复人';
COMMENT ON COLUMN alert_event.resolution_note IS '恢复说明';
COMMENT ON COLUMN alert_event.silenced_by IS '静默人';
COMMENT ON COLUMN alert_event.silenced_from IS '静默前状态';
COMMENT ON COLUMN alert_event.silenced_note IS '静默说明';
COMMENT ON COLUMN alert_event.silenced_at IS '静默时间';
COMMENT ON COLUMN alert_event.tenant_id IS '所属租户（冗余自规则，便于查询）';
COMMENT ON COLUMN alert_event.dept_id IS '归属部门（冗余自规则，便于查询）';

-- ------------------------------------------------------------
-- alert_escalation：告警升级记录表；记录每次升级的级别与通知对象
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS alert_escalation (
    id BIGSERIAL,
    alert_event_id BIGINT NOT NULL,
    level INTEGER NOT NULL,
    trigger_on VARCHAR(16),
    to_role VARCHAR(16),
    to_users VARCHAR(512),
    reason VARCHAR(256),
    triggered_at TIMESTAMP DEFAULT NOW(),
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    dept_id BIGINT,
    CONSTRAINT alert_escalation_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_alert_escalation_event ON alert_escalation USING btree (alert_event_id);
CREATE INDEX IF NOT EXISTS idx_alert_escalation_tenant ON alert_escalation USING btree (tenant_id, alert_event_id);

COMMENT ON TABLE alert_escalation IS '告警升级记录表；记录每次升级的级别与通知对象';
COMMENT ON COLUMN alert_escalation.id IS '主键 ID';
COMMENT ON COLUMN alert_escalation.alert_event_id IS '告警事件 ID';
COMMENT ON COLUMN alert_escalation.level IS '级别';
COMMENT ON COLUMN alert_escalation.trigger_on IS '触发条件';
COMMENT ON COLUMN alert_escalation.to_role IS '通知角色';
COMMENT ON COLUMN alert_escalation.to_users IS '通知用户';
COMMENT ON COLUMN alert_escalation.reason IS '原因';
COMMENT ON COLUMN alert_escalation.triggered_at IS '触发时间';
COMMENT ON COLUMN alert_escalation.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN alert_escalation.dept_id IS '所属部门 ID';

-- ------------------------------------------------------------
-- user_alert_read：用户告警已读表；记录用户对告警的已读状态
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_alert_read (
    user_id VARCHAR(64) NOT NULL,
    alert_event_id BIGINT NOT NULL,
    read_at TIMESTAMP DEFAULT NOW(),
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    dept_id BIGINT,
    CONSTRAINT pk_user_alert_read PRIMARY KEY (tenant_id, user_id, alert_event_id)
);

CREATE INDEX IF NOT EXISTS idx_user_alert_read_tenant ON user_alert_read USING btree (tenant_id, user_id);
CREATE INDEX IF NOT EXISTS idx_user_alert_read_user ON user_alert_read USING btree (user_id);

COMMENT ON TABLE user_alert_read IS '用户告警已读表；记录用户对告警的已读状态';
COMMENT ON COLUMN user_alert_read.user_id IS '用户 ID';
COMMENT ON COLUMN user_alert_read.alert_event_id IS '告警事件 ID';
COMMENT ON COLUMN user_alert_read.read_at IS '已读时间';
COMMENT ON COLUMN user_alert_read.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN user_alert_read.dept_id IS '所属部门 ID';

-- ------------------------------------------------------------
-- notification_channel：通知渠道表；邮件、飞书、Webhook 等渠道配置
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification_channel (
    id BIGSERIAL,
    name VARCHAR(128) NOT NULL,
    type VARCHAR(32) NOT NULL,
    enabled BOOLEAN DEFAULT TRUE,
    config text,
    description VARCHAR(512),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    dept_id BIGINT,
    CONSTRAINT notification_channel_pkey PRIMARY KEY (id),
    CONSTRAINT uk_notification_channel_tenant_name UNIQUE (tenant_id, name)
);

CREATE INDEX IF NOT EXISTS idx_notification_channel_enabled ON notification_channel USING btree (enabled);
CREATE INDEX IF NOT EXISTS idx_notification_channel_tenant ON notification_channel USING btree (tenant_id);
CREATE INDEX IF NOT EXISTS idx_notification_channel_type ON notification_channel USING btree (type);

COMMENT ON TABLE notification_channel IS '通知渠道表；邮件、飞书、Webhook 等渠道配置';
COMMENT ON COLUMN notification_channel.id IS '主键 ID';
COMMENT ON COLUMN notification_channel.name IS '名称';
COMMENT ON COLUMN notification_channel.type IS '类型';
COMMENT ON COLUMN notification_channel.enabled IS '是否启用';
COMMENT ON COLUMN notification_channel.config IS '渠道配置（JSON）';
COMMENT ON COLUMN notification_channel.description IS '描述';
COMMENT ON COLUMN notification_channel.created_at IS '创建时间';
COMMENT ON COLUMN notification_channel.updated_at IS '更新时间';
COMMENT ON COLUMN notification_channel.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN notification_channel.dept_id IS '所属部门 ID';

-- ------------------------------------------------------------
-- notification_record：通知发送记录表；记录通知内容、结果与重试
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification_record (
    id BIGSERIAL,
    alert_event_id BIGINT,
    rule_id BIGINT,
    rule_name VARCHAR(128),
    service VARCHAR(128),
    metric VARCHAR(64),
    channel_id BIGINT,
    channel_name VARCHAR(128),
    channel_type VARCHAR(32),
    target VARCHAR(512),
    title VARCHAR(255),
    content text,
    status VARCHAR(16) NOT NULL,
    error_message text,
    retry_count INTEGER DEFAULT 0,
    max_retry INTEGER DEFAULT 2,
    next_retry_at TIMESTAMP,
    sent_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    dept_id BIGINT,
    CONSTRAINT notification_record_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_notification_record_alert ON notification_record USING btree (alert_event_id);
CREATE INDEX IF NOT EXISTS idx_notification_record_channel ON notification_record USING btree (channel_id);
CREATE INDEX IF NOT EXISTS idx_notification_record_service ON notification_record USING btree (service, created_at);
CREATE INDEX IF NOT EXISTS idx_notification_record_status ON notification_record USING btree (status, next_retry_at);
CREATE INDEX IF NOT EXISTS idx_notification_record_tenant ON notification_record USING btree (tenant_id, dept_id, created_at);

COMMENT ON TABLE notification_record IS '通知发送记录表；记录通知内容、结果与重试';
COMMENT ON COLUMN notification_record.id IS '主键 ID';
COMMENT ON COLUMN notification_record.alert_event_id IS '告警事件 ID';
COMMENT ON COLUMN notification_record.rule_id IS '告警规则 ID';
COMMENT ON COLUMN notification_record.rule_name IS '告警规则名称';
COMMENT ON COLUMN notification_record.service IS '服务名称';
COMMENT ON COLUMN notification_record.metric IS '指标名称';
COMMENT ON COLUMN notification_record.channel_id IS '通知渠道 ID';
COMMENT ON COLUMN notification_record.channel_name IS '通知渠道名称';
COMMENT ON COLUMN notification_record.channel_type IS '通知渠道类型';
COMMENT ON COLUMN notification_record.target IS '操作对象';
COMMENT ON COLUMN notification_record.title IS '标题';
COMMENT ON COLUMN notification_record.content IS '内容';
COMMENT ON COLUMN notification_record.status IS '发送状态：PENDING/SENT/FAILED';
COMMENT ON COLUMN notification_record.error_message IS '错误信息';
COMMENT ON COLUMN notification_record.retry_count IS '重试次数';
COMMENT ON COLUMN notification_record.max_retry IS '最大重试次数';
COMMENT ON COLUMN notification_record.next_retry_at IS '下次重试时间';
COMMENT ON COLUMN notification_record.sent_at IS '发送时间';
COMMENT ON COLUMN notification_record.created_at IS '创建时间';
COMMENT ON COLUMN notification_record.updated_at IS '更新时间';
COMMENT ON COLUMN notification_record.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN notification_record.dept_id IS '所属部门 ID';

-- ------------------------------------------------------------
-- failure_snapshot：故障快照表；保存告警触发时的现场数据
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS failure_snapshot (
    id BIGSERIAL,
    alert_event_id BIGINT NOT NULL,
    rule_id BIGINT,
    rule_name VARCHAR(128),
    service VARCHAR(128),
    metric VARCHAR(64),
    status VARCHAR(16),
    snapshot_json text NOT NULL,
    created_at TIMESTAMP DEFAULT NOW(),
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    dept_id BIGINT,
    CONSTRAINT failure_snapshot_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_failure_snapshot_alert ON failure_snapshot USING btree (alert_event_id);
CREATE INDEX IF NOT EXISTS idx_failure_snapshot_service ON failure_snapshot USING btree (service, created_at);
CREATE INDEX IF NOT EXISTS idx_failure_snapshot_tenant ON failure_snapshot USING btree (tenant_id, dept_id, created_at);

COMMENT ON TABLE failure_snapshot IS '故障快照表；保存告警触发时的现场数据';
COMMENT ON COLUMN failure_snapshot.id IS '主键 ID';
COMMENT ON COLUMN failure_snapshot.alert_event_id IS '告警事件 ID';
COMMENT ON COLUMN failure_snapshot.rule_id IS '告警规则 ID';
COMMENT ON COLUMN failure_snapshot.rule_name IS '告警规则名称';
COMMENT ON COLUMN failure_snapshot.service IS '服务名称';
COMMENT ON COLUMN failure_snapshot.metric IS '指标名称';
COMMENT ON COLUMN failure_snapshot.status IS '状态';
COMMENT ON COLUMN failure_snapshot.snapshot_json IS '快照 JSON';
COMMENT ON COLUMN failure_snapshot.created_at IS '创建时间';
COMMENT ON COLUMN failure_snapshot.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN failure_snapshot.dept_id IS '所属部门 ID';

-- ------------------------------------------------------------
-- diagnosis_record：诊断记录表；保存 AI 诊断的根因、证据与建议
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS diagnosis_record (
    id BIGSERIAL,
    alert_event_id BIGINT NOT NULL,
    snapshot_id BIGINT,
    rule_id BIGINT,
    rule_name VARCHAR(128),
    service VARCHAR(128),
    metric VARCHAR(64),
    status VARCHAR(16) NOT NULL,
    root_cause text,
    evidence text,
    confidence INTEGER,
    suggestions text,
    summary text,
    question VARCHAR(512),
    answer text,
    error_message text,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    duration_ms BIGINT,
    references_json text,
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    dept_id BIGINT,
    CONSTRAINT diagnosis_record_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_diagnosis_alert ON diagnosis_record USING btree (alert_event_id);
CREATE INDEX IF NOT EXISTS idx_diagnosis_rule ON diagnosis_record USING btree (rule_id);
CREATE INDEX IF NOT EXISTS idx_diagnosis_service ON diagnosis_record USING btree (service, created_at);
CREATE INDEX IF NOT EXISTS idx_diagnosis_tenant_dept ON diagnosis_record USING btree (tenant_id, dept_id, created_at);

COMMENT ON TABLE diagnosis_record IS '诊断记录表；保存 AI 诊断的根因、证据与建议';
COMMENT ON COLUMN diagnosis_record.id IS '主键 ID';
COMMENT ON COLUMN diagnosis_record.alert_event_id IS '告警事件 ID';
COMMENT ON COLUMN diagnosis_record.snapshot_id IS '快照 ID';
COMMENT ON COLUMN diagnosis_record.rule_id IS '告警规则 ID';
COMMENT ON COLUMN diagnosis_record.rule_name IS '告警规则名称';
COMMENT ON COLUMN diagnosis_record.service IS '服务名称';
COMMENT ON COLUMN diagnosis_record.metric IS '指标名称';
COMMENT ON COLUMN diagnosis_record.status IS '状态';
COMMENT ON COLUMN diagnosis_record.root_cause IS '根因';
COMMENT ON COLUMN diagnosis_record.evidence IS '证据';
COMMENT ON COLUMN diagnosis_record.confidence IS '置信度';
COMMENT ON COLUMN diagnosis_record.suggestions IS '处置建议';
COMMENT ON COLUMN diagnosis_record.summary IS '摘要';
COMMENT ON COLUMN diagnosis_record.question IS '问题';
COMMENT ON COLUMN diagnosis_record.answer IS '回答内容';
COMMENT ON COLUMN diagnosis_record.error_message IS '错误信息';
COMMENT ON COLUMN diagnosis_record.created_at IS '创建时间';
COMMENT ON COLUMN diagnosis_record.updated_at IS '更新时间';
COMMENT ON COLUMN diagnosis_record.duration_ms IS '耗时（毫秒）';
COMMENT ON COLUMN diagnosis_record.references_json IS '引用 JSON';
COMMENT ON COLUMN diagnosis_record.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN diagnosis_record.dept_id IS '所属部门 ID';

-- ------------------------------------------------------------
-- maintenance_window：维护窗口表；维护期间可抑制相关告警
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS maintenance_window (
    id BIGSERIAL,
    service VARCHAR(128) NOT NULL,
    reason VARCHAR(256) NOT NULL,
    until TIMESTAMP NOT NULL,
    note VARCHAR(500),
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT NOW(),
    ended_at TIMESTAMP,
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    dept_id BIGINT,
    CONSTRAINT maintenance_window_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_maintenance_tenant ON maintenance_window USING btree (tenant_id, dept_id, until);
CREATE INDEX IF NOT EXISTS idx_maintenance_window_active ON maintenance_window USING btree (until, ended_at);
CREATE INDEX IF NOT EXISTS idx_maintenance_window_service ON maintenance_window USING btree (service);

COMMENT ON TABLE maintenance_window IS '维护窗口表；维护期间可抑制相关告警';
COMMENT ON COLUMN maintenance_window.id IS '主键 ID';
COMMENT ON COLUMN maintenance_window.service IS '服务名称';
COMMENT ON COLUMN maintenance_window.reason IS '原因';
COMMENT ON COLUMN maintenance_window.until IS '截止时间';
COMMENT ON COLUMN maintenance_window.note IS '备注';
COMMENT ON COLUMN maintenance_window.created_by IS '创建人';
COMMENT ON COLUMN maintenance_window.created_at IS '创建时间';
COMMENT ON COLUMN maintenance_window.ended_at IS '结束时间';
COMMENT ON COLUMN maintenance_window.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN maintenance_window.dept_id IS '所属部门 ID';

-- ------------------------------------------------------------
-- oncall_member：值班成员表；记录部门值班人员与值班角色
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS oncall_member (
    id BIGSERIAL,
    user_id VARCHAR(32) NOT NULL,
    duty_role VARCHAR(16) NOT NULL,
    added_by VARCHAR(64),
    added_at TIMESTAMP DEFAULT NOW(),
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    dept_id BIGINT,
    CONSTRAINT oncall_member_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_oncall_member_role ON oncall_member USING btree (duty_role);
CREATE INDEX IF NOT EXISTS idx_oncall_tenant ON oncall_member USING btree (tenant_id, dept_id);

COMMENT ON TABLE oncall_member IS '值班成员表；记录部门值班人员与值班角色';
COMMENT ON COLUMN oncall_member.id IS '主键 ID';
COMMENT ON COLUMN oncall_member.user_id IS '用户 ID';
COMMENT ON COLUMN oncall_member.duty_role IS '值班角色';
COMMENT ON COLUMN oncall_member.added_by IS '添加人';
COMMENT ON COLUMN oncall_member.added_at IS '添加时间';
COMMENT ON COLUMN oncall_member.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN oncall_member.dept_id IS '所属部门 ID';

-- ------------------------------------------------------------
-- knowledge_document：知识文档表；保存文档内容、可见性与审核状态
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGSERIAL,
    title VARCHAR(255) NOT NULL,
    doc_type VARCHAR(16) DEFAULT 'MARKDOWN' NOT NULL,
    layer VARCHAR(16) DEFAULT 'PRIVATE' NOT NULL,
    trust_status VARCHAR(16) DEFAULT 'PENDING' NOT NULL,
    version INTEGER DEFAULT 1 NOT NULL,
    owner VARCHAR(64),
    service_ids text,
    tags text,
    hit_count BIGINT DEFAULT 0,
    source VARCHAR(255),
    file_path VARCHAR(512),
    raw_content text,
    chunk_count INTEGER DEFAULT 0,
    submitted_at TIMESTAMP,
    last_review_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    effective_at TIMESTAMP,
    review_at TIMESTAMP,
    conflict_status VARCHAR(16) DEFAULT 'NONE',
    package_version VARCHAR(32),
    released_at TIMESTAMP,
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    dept_id BIGINT,
    visibility VARCHAR(32) DEFAULT 'PERSONAL' NOT NULL,
    review_status VARCHAR(32) DEFAULT 'DRAFT' NOT NULL,
    review_level VARCHAR(16) DEFAULT 'NONE' NOT NULL,
    reviewed_by VARCHAR(64),
    reviewed_at TIMESTAMP,
    review_note VARCHAR(500),
    CONSTRAINT knowledge_document_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_doc_layer ON knowledge_document USING btree (layer);
CREATE INDEX IF NOT EXISTS idx_knowledge_doc_source ON knowledge_document USING btree (source);
CREATE INDEX IF NOT EXISTS idx_knowledge_doc_status ON knowledge_document USING btree (trust_status);
CREATE INDEX IF NOT EXISTS idx_knowledge_doc_tenant_dept ON knowledge_document USING btree (tenant_id, dept_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_doc_title ON knowledge_document USING btree (title);
CREATE INDEX IF NOT EXISTS idx_knowledge_doc_visibility ON knowledge_document USING btree (tenant_id, visibility, review_status);

COMMENT ON TABLE knowledge_document IS '知识文档表；保存文档内容、可见性与审核状态';
COMMENT ON COLUMN knowledge_document.id IS '主键 ID';
COMMENT ON COLUMN knowledge_document.title IS '标题';
COMMENT ON COLUMN knowledge_document.doc_type IS '文档类型';
COMMENT ON COLUMN knowledge_document.layer IS '知识层级';
COMMENT ON COLUMN knowledge_document.trust_status IS '可信状态';
COMMENT ON COLUMN knowledge_document.version IS '版本号';
COMMENT ON COLUMN knowledge_document.owner IS '负责人';
COMMENT ON COLUMN knowledge_document.service_ids IS '关联服务 ID 列表';
COMMENT ON COLUMN knowledge_document.tags IS '标签（JSON）';
COMMENT ON COLUMN knowledge_document.hit_count IS '命中次数';
COMMENT ON COLUMN knowledge_document.source IS '来源';
COMMENT ON COLUMN knowledge_document.file_path IS '文件路径';
COMMENT ON COLUMN knowledge_document.raw_content IS '原始内容';
COMMENT ON COLUMN knowledge_document.chunk_count IS '分块数量';
COMMENT ON COLUMN knowledge_document.submitted_at IS '提交时间';
COMMENT ON COLUMN knowledge_document.last_review_at IS '最近审核时间';
COMMENT ON COLUMN knowledge_document.created_at IS '创建时间';
COMMENT ON COLUMN knowledge_document.updated_at IS '更新时间';
COMMENT ON COLUMN knowledge_document.effective_at IS '生效时间';
COMMENT ON COLUMN knowledge_document.review_at IS '审核时间';
COMMENT ON COLUMN knowledge_document.conflict_status IS '冲突状态';
COMMENT ON COLUMN knowledge_document.package_version IS '知识包版本';
COMMENT ON COLUMN knowledge_document.released_at IS '发布时间';
COMMENT ON COLUMN knowledge_document.tenant_id IS '所属租户；0 表示平台公共知识';
COMMENT ON COLUMN knowledge_document.dept_id IS '归属部门；PUBLIC/平台知识可为空';
COMMENT ON COLUMN knowledge_document.visibility IS '可见性：PERSONAL/DEPT/TENANT/PUBLIC';
COMMENT ON COLUMN knowledge_document.review_status IS '审核状态：DRAFT/PENDING_DEPT/PENDING_TENANT/APPROVED/REJECTED';
COMMENT ON COLUMN knowledge_document.review_level IS '当前审核层级：NONE/DEPT/TENANT';
COMMENT ON COLUMN knowledge_document.reviewed_by IS '最近审核人';
COMMENT ON COLUMN knowledge_document.reviewed_at IS '最近审核时间';
COMMENT ON COLUMN knowledge_document.review_note IS '审核意见/驳回原因';

-- ------------------------------------------------------------
-- knowledge_document_version：知识文档版本表；保存文档历史版本快照
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge_document_version (
    id BIGSERIAL,
    doc_id BIGINT NOT NULL,
    version INTEGER NOT NULL,
    content_snapshot text,
    changed_by VARCHAR(64),
    change_reason VARCHAR(255),
    created_at TIMESTAMP DEFAULT NOW(),
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    dept_id BIGINT,
    CONSTRAINT knowledge_document_version_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_version_doc ON knowledge_document_version USING btree (doc_id, version);
CREATE INDEX IF NOT EXISTS idx_knowledge_version_tenant ON knowledge_document_version USING btree (tenant_id, doc_id);

COMMENT ON TABLE knowledge_document_version IS '知识文档版本表；保存文档历史版本快照';
COMMENT ON COLUMN knowledge_document_version.id IS '主键 ID';
COMMENT ON COLUMN knowledge_document_version.doc_id IS '文档 ID';
COMMENT ON COLUMN knowledge_document_version.version IS '版本号';
COMMENT ON COLUMN knowledge_document_version.content_snapshot IS '内容快照';
COMMENT ON COLUMN knowledge_document_version.changed_by IS '变更人';
COMMENT ON COLUMN knowledge_document_version.change_reason IS '变更原因';
COMMENT ON COLUMN knowledge_document_version.created_at IS '创建时间';
COMMENT ON COLUMN knowledge_document_version.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN knowledge_document_version.dept_id IS '所属部门 ID';

-- ------------------------------------------------------------
-- knowledge_chunk：知识分块表；保存文档切分后的检索分块
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id BIGSERIAL,
    doc_id BIGINT NOT NULL,
    ordinal INTEGER NOT NULL,
    content text NOT NULL,
    section VARCHAR(255),
    vector_id VARCHAR(64),
    created_at TIMESTAMP DEFAULT NOW(),
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    dept_id BIGINT,
    CONSTRAINT knowledge_chunk_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_doc ON knowledge_chunk USING btree (doc_id, ordinal);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_tenant ON knowledge_chunk USING btree (tenant_id, doc_id);

COMMENT ON TABLE knowledge_chunk IS '知识分块表；保存文档切分后的检索分块';
COMMENT ON COLUMN knowledge_chunk.id IS '主键 ID';
COMMENT ON COLUMN knowledge_chunk.doc_id IS '文档 ID';
COMMENT ON COLUMN knowledge_chunk.ordinal IS '分块序号';
COMMENT ON COLUMN knowledge_chunk.content IS '内容';
COMMENT ON COLUMN knowledge_chunk.section IS '章节';
COMMENT ON COLUMN knowledge_chunk.vector_id IS '向量 ID';
COMMENT ON COLUMN knowledge_chunk.created_at IS '创建时间';
COMMENT ON COLUMN knowledge_chunk.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN knowledge_chunk.dept_id IS '所属部门 ID';

-- ------------------------------------------------------------
-- knowledge_case：知识案例表；保存故障案例与处置经验
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge_case (
    id BIGSERIAL,
    source_alert_id BIGINT,
    diagnosis_record_id BIGINT,
    service VARCHAR(128),
    title VARCHAR(255) NOT NULL,
    symptom text,
    root_cause text,
    real_root_cause text,
    evidence text,
    effective_action text,
    confidence INTEGER,
    masked_status VARCHAR(16) DEFAULT 'NONE',
    status VARCHAR(16) DEFAULT 'PENDING' NOT NULL,
    duration_min INTEGER,
    closed_loop_at TIMESTAMP,
    confirmed_by VARCHAR(64),
    cited_count INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    dept_id BIGINT,
    visibility VARCHAR(32) DEFAULT 'TENANT' NOT NULL,
    CONSTRAINT knowledge_case_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_case_alert ON knowledge_case USING btree (source_alert_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_case_diag ON knowledge_case USING btree (diagnosis_record_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_case_service ON knowledge_case USING btree (service, created_at);
CREATE INDEX IF NOT EXISTS idx_knowledge_case_status ON knowledge_case USING btree (status);
CREATE INDEX IF NOT EXISTS idx_knowledge_case_tenant ON knowledge_case USING btree (tenant_id, dept_id, status);

COMMENT ON TABLE knowledge_case IS '知识案例表；保存故障案例与处置经验';
COMMENT ON COLUMN knowledge_case.id IS '主键 ID';
COMMENT ON COLUMN knowledge_case.source_alert_id IS '来源告警 ID';
COMMENT ON COLUMN knowledge_case.diagnosis_record_id IS '诊断记录 ID';
COMMENT ON COLUMN knowledge_case.service IS '服务名称';
COMMENT ON COLUMN knowledge_case.title IS '标题';
COMMENT ON COLUMN knowledge_case.symptom IS '现象';
COMMENT ON COLUMN knowledge_case.root_cause IS '根因';
COMMENT ON COLUMN knowledge_case.real_root_cause IS '真实根因';
COMMENT ON COLUMN knowledge_case.evidence IS '证据';
COMMENT ON COLUMN knowledge_case.effective_action IS '有效处置动作';
COMMENT ON COLUMN knowledge_case.confidence IS '置信度';
COMMENT ON COLUMN knowledge_case.masked_status IS '脱敏状态';
COMMENT ON COLUMN knowledge_case.status IS '案例状态：PENDING/CONFIRMED/REJECTED';
COMMENT ON COLUMN knowledge_case.duration_min IS '持续分钟数';
COMMENT ON COLUMN knowledge_case.closed_loop_at IS '闭环时间';
COMMENT ON COLUMN knowledge_case.confirmed_by IS '确认人';
COMMENT ON COLUMN knowledge_case.cited_count IS '被引用次数';
COMMENT ON COLUMN knowledge_case.created_at IS '创建时间';
COMMENT ON COLUMN knowledge_case.updated_at IS '更新时间';
COMMENT ON COLUMN knowledge_case.tenant_id IS '所属租户';
COMMENT ON COLUMN knowledge_case.dept_id IS '所属部门 ID';
COMMENT ON COLUMN knowledge_case.visibility IS '案例可见性；确认后默认 TENANT';

-- ------------------------------------------------------------
-- knowledge_conflict：知识冲突表；记录相似或矛盾的知识文档
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge_conflict (
    id BIGSERIAL,
    doc_id BIGINT NOT NULL,
    matched_doc_id BIGINT,
    conflict_type VARCHAR(16) NOT NULL,
    similarity numeric(5,4),
    status VARCHAR(16) DEFAULT 'PENDING' NOT NULL,
    decision VARCHAR(16),
    resolved_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT NOW(),
    resolved_at TIMESTAMP,
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    dept_id BIGINT,
    CONSTRAINT knowledge_conflict_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_conflict_doc ON knowledge_conflict USING btree (doc_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_conflict_status ON knowledge_conflict USING btree (status);
CREATE INDEX IF NOT EXISTS idx_knowledge_conflict_tenant ON knowledge_conflict USING btree (tenant_id, dept_id, status);

COMMENT ON TABLE knowledge_conflict IS '知识冲突表；记录相似或矛盾的知识文档';
COMMENT ON COLUMN knowledge_conflict.id IS '主键 ID';
COMMENT ON COLUMN knowledge_conflict.doc_id IS '文档 ID';
COMMENT ON COLUMN knowledge_conflict.matched_doc_id IS '匹配文档 ID';
COMMENT ON COLUMN knowledge_conflict.conflict_type IS '冲突类型';
COMMENT ON COLUMN knowledge_conflict.similarity IS '相似度';
COMMENT ON COLUMN knowledge_conflict.status IS '状态';
COMMENT ON COLUMN knowledge_conflict.decision IS '处理决定';
COMMENT ON COLUMN knowledge_conflict.resolved_by IS '恢复人';
COMMENT ON COLUMN knowledge_conflict.created_at IS '创建时间';
COMMENT ON COLUMN knowledge_conflict.resolved_at IS '恢复时间';
COMMENT ON COLUMN knowledge_conflict.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN knowledge_conflict.dept_id IS '所属部门 ID';

-- ------------------------------------------------------------
-- knowledge_hit_log：知识命中日志表；记录检索命中的文档与来源
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge_hit_log (
    id BIGSERIAL,
    doc_id BIGINT NOT NULL,
    query text,
    hit_source VARCHAR(16) DEFAULT 'ASK' NOT NULL,
    channel VARCHAR(32),
    created_at TIMESTAMP DEFAULT NOW(),
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    dept_id BIGINT,
    CONSTRAINT knowledge_hit_log_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_hit_doc ON knowledge_hit_log USING btree (doc_id, created_at);
CREATE INDEX IF NOT EXISTS idx_knowledge_hit_source ON knowledge_hit_log USING btree (hit_source, created_at);
CREATE INDEX IF NOT EXISTS idx_knowledge_hit_tenant ON knowledge_hit_log USING btree (tenant_id, dept_id, created_at);

COMMENT ON TABLE knowledge_hit_log IS '知识命中日志表；记录检索命中的文档与来源';
COMMENT ON COLUMN knowledge_hit_log.id IS '主键 ID';
COMMENT ON COLUMN knowledge_hit_log.doc_id IS '文档 ID';
COMMENT ON COLUMN knowledge_hit_log.query IS '查询内容';
COMMENT ON COLUMN knowledge_hit_log.hit_source IS '命中来源';
COMMENT ON COLUMN knowledge_hit_log.channel IS '渠道';
COMMENT ON COLUMN knowledge_hit_log.created_at IS '创建时间';
COMMENT ON COLUMN knowledge_hit_log.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN knowledge_hit_log.dept_id IS '所属部门 ID';

-- ------------------------------------------------------------
-- ops_audit_log：审计日志表；记录登录、审核、权限变更等操作留痕
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_audit_log (
    id BIGSERIAL,
    username VARCHAR(64),
    operation VARCHAR(64) NOT NULL,
    target VARCHAR(255),
    method VARCHAR(16),
    uri VARCHAR(255),
    params text,
    result VARCHAR(16) NOT NULL,
    error_message text,
    ip VARCHAR(64),
    user_agent VARCHAR(512),
    duration_ms BIGINT,
    created_at TIMESTAMP DEFAULT NOW(),
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    dept_id BIGINT,
    CONSTRAINT ops_audit_log_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_audit_created_at ON ops_audit_log USING btree (created_at);
CREATE INDEX IF NOT EXISTS idx_audit_operation ON ops_audit_log USING btree (operation);
CREATE INDEX IF NOT EXISTS idx_audit_result ON ops_audit_log USING btree (result);
CREATE INDEX IF NOT EXISTS idx_audit_tenant ON ops_audit_log USING btree (tenant_id, dept_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_username ON ops_audit_log USING btree (username);

COMMENT ON TABLE ops_audit_log IS '审计日志表；记录登录、审核、权限变更等操作留痕';
COMMENT ON COLUMN ops_audit_log.id IS '主键 ID';
COMMENT ON COLUMN ops_audit_log.username IS '用户名';
COMMENT ON COLUMN ops_audit_log.operation IS '操作类型';
COMMENT ON COLUMN ops_audit_log.target IS '操作对象';
COMMENT ON COLUMN ops_audit_log.method IS 'HTTP 方法';
COMMENT ON COLUMN ops_audit_log.uri IS '请求 URI';
COMMENT ON COLUMN ops_audit_log.params IS '请求参数';
COMMENT ON COLUMN ops_audit_log.result IS '结果';
COMMENT ON COLUMN ops_audit_log.error_message IS '错误信息';
COMMENT ON COLUMN ops_audit_log.ip IS 'IP 地址';
COMMENT ON COLUMN ops_audit_log.user_agent IS 'User-Agent';
COMMENT ON COLUMN ops_audit_log.duration_ms IS '耗时（毫秒）';
COMMENT ON COLUMN ops_audit_log.created_at IS '创建时间';
COMMENT ON COLUMN ops_audit_log.tenant_id IS '所属租户';
COMMENT ON COLUMN ops_audit_log.dept_id IS '操作人所属部门';

-- ------------------------------------------------------------
-- ops_conversation：对话会话表；保存会话元信息与最近活跃时间
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_conversation (
    id VARCHAR(36) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    title VARCHAR(128),
    username VARCHAR(64) NOT NULL,
    message_count INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    last_active_at TIMESTAMP DEFAULT NOW(),
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    CONSTRAINT ops_conversation_pkey PRIMARY KEY (id),
    CONSTRAINT uk_conversation_tenant_session UNIQUE (tenant_id, session_id)
);

CREATE INDEX IF NOT EXISTS idx_conv_last_active ON ops_conversation USING btree (username, last_active_at DESC);
CREATE INDEX IF NOT EXISTS idx_conv_username ON ops_conversation USING btree (username);
CREATE INDEX IF NOT EXISTS idx_conversation_tenant ON ops_conversation USING btree (tenant_id, last_active_at DESC);

COMMENT ON TABLE ops_conversation IS '对话会话表；保存会话元信息与最近活跃时间';
COMMENT ON COLUMN ops_conversation.id IS '主键 ID';
COMMENT ON COLUMN ops_conversation.session_id IS '会话 ID';
COMMENT ON COLUMN ops_conversation.title IS '会话标题';
COMMENT ON COLUMN ops_conversation.username IS '用户名';
COMMENT ON COLUMN ops_conversation.message_count IS '消息数量';
COMMENT ON COLUMN ops_conversation.created_at IS '创建时间';
COMMENT ON COLUMN ops_conversation.updated_at IS '更新时间';
COMMENT ON COLUMN ops_conversation.last_active_at IS '最近活跃时间';
COMMENT ON COLUMN ops_conversation.tenant_id IS '所属租户';

-- ------------------------------------------------------------
-- ops_message：对话消息表；保存会话中的用户/助手消息
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ops_message (
    id BIGSERIAL,
    session_id VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,
    content text NOT NULL,
    reasoning text,
    tool_calls jsonb,
    tokens_used INTEGER,
    created_at TIMESTAMP DEFAULT NOW(),
    tenant_id VARCHAR(32) DEFAULT 't-default' NOT NULL,
    CONSTRAINT ops_message_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_message_tenant ON ops_message USING btree (tenant_id, session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_msg_session_id ON ops_message USING btree (session_id, created_at);

COMMENT ON TABLE ops_message IS '对话消息表；保存会话中的用户/助手消息';
COMMENT ON COLUMN ops_message.id IS '主键 ID';
COMMENT ON COLUMN ops_message.session_id IS '会话 ID';
COMMENT ON COLUMN ops_message.role IS '消息角色：user/assistant/tool';
COMMENT ON COLUMN ops_message.content IS '内容';
COMMENT ON COLUMN ops_message.reasoning IS '推理过程';
COMMENT ON COLUMN ops_message.tool_calls IS '工具调用（JSON）';
COMMENT ON COLUMN ops_message.tokens_used IS 'Token 数';
COMMENT ON COLUMN ops_message.created_at IS '创建时间';
COMMENT ON COLUMN ops_message.tenant_id IS '所属租户（冗余自会话，便于隔离）';

-- ------------------------------------------------------------
-- vector_store：Spring AI 向量存储表；保存知识分块的向量与元数据
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS vector_store (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    content text,
    metadata jsonb,
    embedding vector(1024),
    CONSTRAINT vector_store_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS spring_ai_vector_index ON vector_store USING hnsw (embedding vector_cosine_ops);

COMMENT ON TABLE vector_store IS 'Spring AI 向量存储表；保存知识分块的向量与元数据';
COMMENT ON COLUMN vector_store.id IS '主键 ID';
COMMENT ON COLUMN vector_store.content IS '文本内容';
COMMENT ON COLUMN vector_store.metadata IS '元数据（JSON）';
COMMENT ON COLUMN vector_store.embedding IS '向量（1024 维）';

