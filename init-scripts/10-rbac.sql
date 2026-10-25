-- ============================================================
-- 10-rbac.sql
-- RBAC v1 / 多租户基础数据迁移（P0）
--
-- 说明：
--   1. 本脚本只做「建表 + 加列 + 回填 + 约束/索引」；
--   2. 全部语句幂等，可重复执行；
--   3. 原 01~09 脚本保持不变，所有变更集中在本文件；
--   4. 引导租户固定为 t-default；平台公共数据使用保留租户 0；
--   5. 存量数据默认归入引导租户；内置知识归平台（tenant_id=0 + PUBLIC）；
--   6. 为兼容尚未改造的旧代码，tenant_id 先给 DEFAULT 't-default'，
--      待 P1~P4 代码全部改为从上下文取值后，再在 P6 移除默认值。
-- ============================================================

-- ============================================================
-- 一、组织与权限基座（新表）
-- ============================================================

-- 企业 / 租户
CREATE TABLE IF NOT EXISTS ops_tenant (
    id                    VARCHAR(32)  PRIMARY KEY,                    -- 租户 ID（平台保留 0）
    code                  VARCHAR(64)  NOT NULL UNIQUE,               -- 租户编码（全局唯一）
    name                  VARCHAR(128) NOT NULL,                      -- 企业名称
    status                VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',     -- ACTIVE / SUSPENDED
    knowledge_review_mode VARCHAR(32)  NOT NULL DEFAULT 'DEPT_ONLY',  -- DEPT_ONLY / DEPT_AND_TENANT / NONE
    review_four_eyes      BOOLEAN      NOT NULL DEFAULT TRUE,         -- 审核人是否不能等于上传人
    created_at            TIMESTAMP    DEFAULT NOW(),
    updated_at            TIMESTAMP    DEFAULT NOW()
);
COMMENT ON TABLE  ops_tenant IS '企业/租户表；多租户数据隔离的根';
COMMENT ON COLUMN ops_tenant.id IS '租户 ID；平台公共数据保留 0';
COMMENT ON COLUMN ops_tenant.knowledge_review_mode IS '知识审核模式：DEPT_ONLY=仅部门；DEPT_AND_TENANT=部门+企业；NONE=免审';
COMMENT ON COLUMN ops_tenant.review_four_eyes IS '四眼原则开关：TRUE 时审核人不能是上传人';

-- 部门树（path 物化路径，便于“上级看下级”）
CREATE TABLE IF NOT EXISTS ops_dept (
    id          BIGSERIAL    PRIMARY KEY,
    tenant_id   VARCHAR(32)  NOT NULL,
    parent_id   BIGINT,                                                -- 父部门；根部门为空
    code        VARCHAR(64)  NOT NULL,                                 -- 租户内部门编码（幂等种子用）
    name        VARCHAR(128) NOT NULL,                                 -- 部门名称
    path        VARCHAR(512) NOT NULL,                                 -- 物化路径，如 /1/5/
    level       INT          NOT NULL DEFAULT 1,                       -- 层级，根为 1
    sort        INT          NOT NULL DEFAULT 0,                       -- 同级排序
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',                -- ACTIVE / DISABLED
    created_at  TIMESTAMP    DEFAULT NOW(),
    updated_at  TIMESTAMP    DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_dept_tenant_code ON ops_dept(tenant_id, code);
CREATE INDEX IF NOT EXISTS idx_dept_tenant ON ops_dept(tenant_id);
CREATE INDEX IF NOT EXISTS idx_dept_path   ON ops_dept(tenant_id, path);
COMMENT ON TABLE  ops_dept IS '部门树；支持多级，上级部门的数据范围包含全部下级';
COMMENT ON COLUMN ops_dept.path IS '物化路径（/父ID/子ID/），用于 path LIKE 子树查询';
COMMENT ON COLUMN ops_dept.code IS '租户内唯一编码，便于迁移与外部引用';

-- 登录身份（一个用户可绑定多种登录方式：邮箱 / 微信 / OIDC / SSO）
CREATE TABLE IF NOT EXISTS ops_user_identity (
    id               BIGSERIAL    PRIMARY KEY,
    user_id          VARCHAR(32)  NOT NULL,                            -- 关联 ops_user.id
    provider         VARCHAR(32)  NOT NULL,                            -- LOCAL / WECHAT / OIDC / SSO
    identifier       VARCHAR(255) NOT NULL,                            -- 邮箱 / openid / sub
    credential_hash  VARCHAR(255),                                     -- LOCAL 密码 BCrypt；第三方为空
    extra_json       TEXT,                                             -- 头像、unionid 等扩展信息
    need_bind_email  BOOLEAN      NOT NULL DEFAULT FALSE,              -- 迁移账号首次登录需绑定真实邮箱
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',           -- ACTIVE / DISABLED
    last_login_at    TIMESTAMP,
    created_at       TIMESTAMP    DEFAULT NOW(),
    CONSTRAINT uk_identity_provider_identifier UNIQUE (provider, identifier)
);
CREATE INDEX IF NOT EXISTS idx_identity_user ON ops_user_identity(user_id);
COMMENT ON TABLE  ops_user_identity IS '登录身份表；用户与登录方式解耦，新增微信/OIDC 只需增加一行';
COMMENT ON COLUMN ops_user_identity.provider IS '登录方式：LOCAL=邮箱密码；WECHAT/OIDC/SSO 为后续扩展';
COMMENT ON COLUMN ops_user_identity.identifier IS '登录标识：LOCAL 为邮箱，第三方为 openid/sub';
COMMENT ON COLUMN ops_user_identity.need_bind_email IS '存量迁移账号为 TRUE，首次登录后应绑定真实邮箱';

-- 角色
CREATE TABLE IF NOT EXISTS ops_role (
    id          BIGSERIAL    PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,                          -- 角色编码
    name        VARCHAR(64)  NOT NULL,                                 -- 角色名称
    scope       VARCHAR(16)  NOT NULL,                                 -- PLATFORM / TENANT / DEPT
    builtin     BOOLEAN      NOT NULL DEFAULT FALSE,                   -- 内置角色
    created_at  TIMESTAMP    DEFAULT NOW()
);
COMMENT ON TABLE  ops_role IS '角色表；角色是岗位，权限点才是能力';
COMMENT ON COLUMN ops_role.scope IS '角色作用域：PLATFORM 平台 / TENANT 企业 / DEPT 部门';

-- 权限点
CREATE TABLE IF NOT EXISTS ops_permission (
    id      BIGSERIAL    PRIMARY KEY,
    code    VARCHAR(128) NOT NULL UNIQUE,                              -- 权限编码，如 knowledge:review
    name    VARCHAR(128) NOT NULL,                                     -- 权限名称
    module  VARCHAR(64)                                                -- 所属模块
);
COMMENT ON TABLE  ops_permission IS '权限点表；代码只认权限点，不写死角色';

-- 角色-权限
CREATE TABLE IF NOT EXISTS ops_role_permission (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id)
);
COMMENT ON TABLE ops_role_permission IS '角色与权限点多对多关系';

-- 用户-角色（支持多角色）
CREATE TABLE IF NOT EXISTS ops_user_role (
    user_id VARCHAR(32) NOT NULL,
    role_id BIGINT      NOT NULL,
    PRIMARY KEY (user_id, role_id)
);
COMMENT ON TABLE ops_user_role IS '用户与角色多对多关系；权限取并集';

-- ============================================================
-- 二、引导租户 / 默认部门 / 内置角色 / 权限点
-- ============================================================

-- 引导租户：存量数据全部归入该租户
INSERT INTO ops_tenant (id, code, name, status, knowledge_review_mode, review_four_eyes)
VALUES ('t-default', 'DEFAULT', '默认企业', 'ACTIVE', 'DEPT_ONLY', TRUE)
ON CONFLICT (id) DO NOTHING;

-- 平台保留租户：内置知识（PUBLIC）归属；平台管理员可挂在该租户下
INSERT INTO ops_tenant (id, code, name, status, knowledge_review_mode, review_four_eyes)
VALUES ('0', 'PLATFORM', '平台', 'ACTIVE', 'DEPT_ONLY', TRUE)
ON CONFLICT (id) DO NOTHING;

-- 默认部门：存量用户/服务/知识先挂到这里，后续可人工拆分
INSERT INTO ops_dept (tenant_id, parent_id, code, name, path, level, sort)
VALUES ('t-default', NULL, 'default', '默认部门', '/', 1, 0)
ON CONFLICT (tenant_id, code) DO NOTHING;

-- 回填默认部门 path（BIGSERIAL 生成 id 后才有最终 path）
UPDATE ops_dept
SET path = '/' || id::text || '/'
WHERE tenant_id = 't-default' AND code = 'default' AND (path = '/' OR path IS NULL);

-- 内置角色
INSERT INTO ops_role (code, name, scope, builtin) VALUES
    ('PLATFORM_ADMIN', '平台管理员', 'PLATFORM', TRUE),
    ('TENANT_ADMIN',   '企业管理员', 'TENANT',   TRUE),
    ('DEPT_ADMIN',     '部门管理员', 'DEPT',     TRUE),
    ('CONTRIBUTOR',    '运维工程师', 'DEPT',     TRUE),
    ('VIEWER',         '只读成员',   'DEPT',     TRUE)
ON CONFLICT (code) DO NOTHING;

-- 内置权限点
INSERT INTO ops_permission (code, name, module) VALUES
    ('tenant:manage',       '租户管理',       'tenant'),
    ('tenant:config',       '租户配置',       'tenant'),
    ('dept:manage',         '部门管理',       'org'),
    ('dept:read',           '部门查看',       'org'),
    ('user:manage',         '用户管理',       'org'),
    ('user:read',           '用户查看',       'org'),
    ('role:manage',         '角色管理',       'org'),
    ('knowledge:read',      '知识查看',       'knowledge'),
    ('knowledge:write',     '知识上传/编辑',  'knowledge'),
    ('knowledge:review',    '知识审核',       'knowledge'),
    ('knowledge:publish',   '知识发布',       'knowledge'),
    ('knowledge:delete',    '知识删除',       'knowledge'),
    ('knowledge:admin',     '知识管理',       'knowledge'),
    ('service:read',        '服务查看',       'service'),
    ('service:manage',      '服务管理',       'service'),
    ('alert:read',          '告警查看',       'alert'),
    ('alert:ack',           '告警认领/确认',  'alert'),
    ('alert:resolve',       '告警恢复',       'alert'),
    ('alert:silence',       '告警静默',       'alert'),
    ('diagnosis:read',      '诊断查看',       'diagnosis'),
    ('diagnosis:trigger',   '诊断触发',       'diagnosis'),
    ('notification:manage', '通知渠道管理',   'notification'),
    ('maintenance:manage',  '维护窗口管理',   'maintenance'),
    ('oncall:manage',       '值班管理',       'oncall'),
    ('audit:read',          '审计查看',       'audit')
ON CONFLICT (code) DO NOTHING;

-- 角色 -> 权限映射（首版；后续可在角色权限页调整）
-- 平台管理员：租户/审计/只读查看，不参与企业内容审核
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM ops_role r
JOIN ops_permission p ON p.code IN (
    'tenant:manage', 'tenant:config', 'audit:read',
    'service:read', 'alert:read', 'knowledge:read'
)
WHERE r.code = 'PLATFORM_ADMIN'
ON CONFLICT DO NOTHING;

-- 企业管理员：本企业全部部门与内容
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM ops_role r
JOIN ops_permission p ON p.code IN (
    'dept:manage', 'dept:read', 'user:manage', 'user:read', 'role:manage',
    'knowledge:read', 'knowledge:write', 'knowledge:review', 'knowledge:publish',
    'knowledge:delete', 'knowledge:admin',
    'service:read', 'service:manage',
    'alert:read', 'alert:ack', 'alert:resolve', 'alert:silence',
    'diagnosis:read', 'diagnosis:trigger',
    'notification:manage', 'maintenance:manage', 'oncall:manage', 'audit:read'
)
WHERE r.code = 'TENANT_ADMIN'
ON CONFLICT DO NOTHING;

-- 部门管理员：本部门 + 子部门，负责审核
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM ops_role r
JOIN ops_permission p ON p.code IN (
    'dept:read', 'user:read',
    'knowledge:read', 'knowledge:write', 'knowledge:review', 'knowledge:publish',
    'service:read',
    'alert:read', 'alert:ack', 'alert:resolve', 'alert:silence',
    'diagnosis:read', 'diagnosis:trigger',
    'maintenance:manage', 'oncall:manage'
)
WHERE r.code = 'DEPT_ADMIN'
ON CONFLICT DO NOTHING;

-- 运维工程师：上传/处置/诊断
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM ops_role r
JOIN ops_permission p ON p.code IN (
    'knowledge:read', 'knowledge:write',
    'service:read',
    'alert:read', 'alert:ack', 'alert:resolve', 'alert:silence',
    'diagnosis:read', 'diagnosis:trigger'
)
WHERE r.code = 'CONTRIBUTOR'
ON CONFLICT DO NOTHING;

-- 只读成员：只读
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM ops_role r
JOIN ops_permission p ON p.code IN (
    'knowledge:read', 'service:read', 'alert:read', 'diagnosis:read'
)
WHERE r.code = 'VIEWER'
ON CONFLICT DO NOTHING;

-- ============================================================
-- 三、现有业务表增加租户/部门/可见性字段
--    统一先给 DEFAULT，兼容尚未改造的旧代码；P6 再移除默认值
-- ============================================================

-- 用户
ALTER TABLE ops_user ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) NOT NULL DEFAULT 't-default';
ALTER TABLE ops_user ADD COLUMN IF NOT EXISTS dept_id   BIGINT;

-- 服务
ALTER TABLE ops_service ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) NOT NULL DEFAULT 't-default';
ALTER TABLE ops_service ADD COLUMN IF NOT EXISTS dept_id   BIGINT;

-- 指标采样
ALTER TABLE metric_sample ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) NOT NULL DEFAULT 't-default';

-- 告警规则 / 事件 / 已读 / 升级
ALTER TABLE alert_rule                   ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) NOT NULL DEFAULT 't-default';
ALTER TABLE alert_rule                   ADD COLUMN IF NOT EXISTS dept_id   BIGINT;
ALTER TABLE alert_event                  ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) NOT NULL DEFAULT 't-default';
ALTER TABLE alert_event                  ADD COLUMN IF NOT EXISTS dept_id   BIGINT;
ALTER TABLE user_alert_read              ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) NOT NULL DEFAULT 't-default';
ALTER TABLE alert_rule_escalation_level  ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) NOT NULL DEFAULT 't-default';
ALTER TABLE alert_escalation              ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) NOT NULL DEFAULT 't-default';

-- 通知 / 快照 / 诊断
ALTER TABLE notification_channel ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) NOT NULL DEFAULT 't-default';
ALTER TABLE notification_record  ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) NOT NULL DEFAULT 't-default';
ALTER TABLE notification_record  ADD COLUMN IF NOT EXISTS dept_id   BIGINT;
ALTER TABLE failure_snapshot     ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) NOT NULL DEFAULT 't-default';
ALTER TABLE failure_snapshot     ADD COLUMN IF NOT EXISTS dept_id   BIGINT;
ALTER TABLE diagnosis_record     ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) NOT NULL DEFAULT 't-default';
ALTER TABLE diagnosis_record     ADD COLUMN IF NOT EXISTS dept_id   BIGINT;

-- 知识库：租户/部门 + 可见性 + 审核状态
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS tenant_id     VARCHAR(32) NOT NULL DEFAULT 't-default';
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS dept_id       BIGINT;
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS visibility    VARCHAR(32) NOT NULL DEFAULT 'PERSONAL'; -- PERSONAL/DEPT/TENANT/PUBLIC
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS review_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT';    -- DRAFT/PENDING_DEPT/PENDING_TENANT/APPROVED/REJECTED
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS review_level  VARCHAR(16) NOT NULL DEFAULT 'NONE';     -- NONE/DEPT/TENANT
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS reviewed_by   VARCHAR(64);
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS reviewed_at   TIMESTAMP;
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS review_note   VARCHAR(500);

ALTER TABLE knowledge_chunk            ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) NOT NULL DEFAULT 't-default';
ALTER TABLE knowledge_chunk            ADD COLUMN IF NOT EXISTS dept_id   BIGINT;
ALTER TABLE knowledge_case             ADD COLUMN IF NOT EXISTS tenant_id  VARCHAR(32) NOT NULL DEFAULT 't-default';
ALTER TABLE knowledge_case             ADD COLUMN IF NOT EXISTS dept_id    BIGINT;
ALTER TABLE knowledge_case             ADD COLUMN IF NOT EXISTS visibility VARCHAR(32) NOT NULL DEFAULT 'TENANT';
ALTER TABLE knowledge_hit_log          ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) NOT NULL DEFAULT 't-default';
ALTER TABLE knowledge_hit_log          ADD COLUMN IF NOT EXISTS dept_id   BIGINT;
ALTER TABLE knowledge_conflict         ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) NOT NULL DEFAULT 't-default';
ALTER TABLE knowledge_conflict         ADD COLUMN IF NOT EXISTS dept_id   BIGINT;
ALTER TABLE knowledge_document_version ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) NOT NULL DEFAULT 't-default';
ALTER TABLE knowledge_document_version ADD COLUMN IF NOT EXISTS dept_id   BIGINT;

-- 维护 / 值班 / 审计 / 会话
ALTER TABLE maintenance_window ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) NOT NULL DEFAULT 't-default';
ALTER TABLE maintenance_window ADD COLUMN IF NOT EXISTS dept_id   BIGINT;
ALTER TABLE oncall_member      ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) NOT NULL DEFAULT 't-default';
ALTER TABLE oncall_member      ADD COLUMN IF NOT EXISTS dept_id   BIGINT;
ALTER TABLE ops_audit_log      ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) NOT NULL DEFAULT 't-default';
ALTER TABLE ops_audit_log      ADD COLUMN IF NOT EXISTS dept_id   BIGINT;
ALTER TABLE ops_conversation   ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) NOT NULL DEFAULT 't-default';
ALTER TABLE ops_message        ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(32) NOT NULL DEFAULT 't-default';

-- ============================================================
-- 四、存量数据回填
-- ============================================================

-- 4.1 用户：归引导租户 + 默认部门
UPDATE ops_user SET tenant_id = 't-default' WHERE tenant_id IS NULL;
UPDATE ops_user
SET dept_id = (SELECT id FROM ops_dept WHERE tenant_id = 't-default' AND code = 'default')
WHERE dept_id IS NULL;

-- 4.2 登录身份：存量用户复制为 LOCAL 身份（邮箱占位 username@local）
--     说明：真实邮箱由管理员/首次登录绑定；BCrypt 密码原样迁移。
INSERT INTO ops_user_identity (user_id, provider, identifier, credential_hash, need_bind_email, status)
SELECT u.id, 'LOCAL', u.username || '@local', u.password, TRUE,
       CASE WHEN u.enabled = FALSE THEN 'DISABLED' ELSE 'ACTIVE' END
FROM ops_user u
WHERE NOT EXISTS (
    SELECT 1 FROM ops_user_identity i
    WHERE i.provider = 'LOCAL' AND i.identifier = u.username || '@local'
);

-- 4.3 用户角色：ADMIN -> 平台管理员 + 企业管理员（引导期双角色，后续可调整）
--               OPERATOR -> 运维工程师；VIEWER -> 只读成员
INSERT INTO ops_user_role (user_id, role_id)
SELECT u.id, r.id
FROM ops_user u
JOIN ops_role r ON (
    (u.role = 'ADMIN'    AND r.code IN ('PLATFORM_ADMIN', 'TENANT_ADMIN')) OR
    (u.role = 'OPERATOR' AND r.code = 'CONTRIBUTOR') OR
    (u.role = 'VIEWER'   AND r.code = 'VIEWER')
)
WHERE NOT EXISTS (
    SELECT 1 FROM ops_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id
);

-- 兜底：未匹配到角色的用户至少给只读成员，避免无权限
INSERT INTO ops_user_role (user_id, role_id)
SELECT u.id, r.id
FROM ops_user u
CROSS JOIN ops_role r
WHERE r.code = 'VIEWER'
  AND NOT EXISTS (SELECT 1 FROM ops_user_role ur WHERE ur.user_id = u.id)
ON CONFLICT DO NOTHING;

-- 4.4 服务：默认部门
UPDATE ops_service
SET dept_id = (SELECT id FROM ops_dept WHERE tenant_id = 't-default' AND code = 'default')
WHERE dept_id IS NULL;

-- 4.5 指标采样：跟随服务租户
UPDATE metric_sample m
SET tenant_id = s.tenant_id
FROM ops_service s
WHERE m.service = s.name;

-- 4.6 告警规则 / 事件：跟随服务与规则
UPDATE alert_rule r
SET tenant_id = s.tenant_id, dept_id = s.dept_id
FROM ops_service s
WHERE r.service = s.name;

UPDATE alert_rule
SET dept_id = (SELECT id FROM ops_dept WHERE tenant_id = 't-default' AND code = 'default')
WHERE dept_id IS NULL;

UPDATE alert_event e
SET tenant_id = r.tenant_id, dept_id = r.dept_id
FROM alert_rule r
WHERE e.rule_id = r.id;

UPDATE alert_event
SET dept_id = (SELECT id FROM ops_dept WHERE tenant_id = 't-default' AND code = 'default')
WHERE dept_id IS NULL;

UPDATE alert_rule_escalation_level l
SET tenant_id = r.tenant_id
FROM alert_rule r
WHERE l.rule_id = r.id;

UPDATE alert_escalation e
SET tenant_id = a.tenant_id
FROM alert_event a
WHERE e.alert_event_id = a.id;

UPDATE user_alert_read uar
SET tenant_id = u.tenant_id
FROM ops_user u
WHERE uar.user_id = u.username;

-- 4.7 通知 / 快照 / 诊断
UPDATE notification_record n
SET tenant_id = s.tenant_id, dept_id = s.dept_id
FROM ops_service s
WHERE n.service = s.name;

UPDATE notification_record
SET dept_id = (SELECT id FROM ops_dept WHERE tenant_id = 't-default' AND code = 'default')
WHERE dept_id IS NULL;

UPDATE failure_snapshot f
SET tenant_id = a.tenant_id, dept_id = a.dept_id
FROM alert_event a
WHERE f.alert_event_id = a.id;

UPDATE failure_snapshot
SET dept_id = (SELECT id FROM ops_dept WHERE tenant_id = 't-default' AND code = 'default')
WHERE dept_id IS NULL;

UPDATE diagnosis_record d
SET tenant_id = a.tenant_id, dept_id = a.dept_id
FROM alert_event a
WHERE d.alert_event_id = a.id;

UPDATE diagnosis_record
SET dept_id = (SELECT id FROM ops_dept WHERE tenant_id = 't-default' AND code = 'default')
WHERE dept_id IS NULL;

-- 4.8 知识库：内置归平台 PUBLIC；案例归引导租户 TENANT；私有归个人
UPDATE knowledge_document
SET tenant_id = '0',
    visibility = 'PUBLIC',
    review_status = 'APPROVED',
    review_level = 'NONE',
    dept_id = NULL,
    owner = COALESCE(owner, 'platform')
WHERE layer = 'BUILTIN';

UPDATE knowledge_document
SET tenant_id = 't-default',
    visibility = 'TENANT',
    review_status = 'APPROVED',
    review_level = 'NONE'
WHERE layer = 'CASE';

-- 私有文档：能匹配到上传人部门则继承，否则稍后回填默认部门
UPDATE knowledge_document d
SET dept_id = u.dept_id
FROM ops_user u
WHERE d.layer = 'PRIVATE' AND d.owner = u.username;

-- 私有文档：可见性收敛为个人；已发布过的保留 APPROVED（仅本人可见），其余为 DRAFT
UPDATE knowledge_document
SET visibility = 'PERSONAL',
    review_status = CASE WHEN trust_status = 'VERIFIED' THEN 'APPROVED' ELSE 'DRAFT' END,
    review_level = 'NONE'
WHERE layer = 'PRIVATE';

UPDATE knowledge_document
SET dept_id = (SELECT id FROM ops_dept WHERE tenant_id = 't-default' AND code = 'default')
WHERE tenant_id = 't-default' AND dept_id IS NULL;

-- 分块 / 命中 / 冲突 / 版本：继承文档的租户与部门
UPDATE knowledge_chunk c
SET tenant_id = d.tenant_id, dept_id = d.dept_id
FROM knowledge_document d
WHERE c.doc_id = d.id;

UPDATE knowledge_hit_log h
SET tenant_id = d.tenant_id, dept_id = d.dept_id
FROM knowledge_document d
WHERE h.doc_id = d.id;

UPDATE knowledge_conflict c
SET tenant_id = d.tenant_id, dept_id = d.dept_id
FROM knowledge_document d
WHERE c.doc_id = d.id;

UPDATE knowledge_document_version v
SET tenant_id = d.tenant_id, dept_id = d.dept_id
FROM knowledge_document d
WHERE v.doc_id = d.id;

-- 案例表：默认引导租户 + 默认部门 + 企业可见
UPDATE knowledge_case
SET tenant_id = 't-default',
    dept_id = (SELECT id FROM ops_dept WHERE tenant_id = 't-default' AND code = 'default'),
    visibility = 'TENANT'
WHERE tenant_id IS NULL OR dept_id IS NULL OR visibility IS NULL;

-- 4.9 维护 / 值班
UPDATE maintenance_window w
SET tenant_id = s.tenant_id, dept_id = s.dept_id
FROM ops_service s
WHERE w.service = s.name;

UPDATE maintenance_window
SET dept_id = (SELECT id FROM ops_dept WHERE tenant_id = 't-default' AND code = 'default')
WHERE dept_id IS NULL;

-- 值班表 user_id 为 BIGINT，历史上与 ops_user.id（VARCHAR）类型不一致；
-- 这里先统一归默认部门，后续 P4 改造时再按用户重新绑定部门。
UPDATE oncall_member
SET dept_id = (SELECT id FROM ops_dept WHERE tenant_id = 't-default' AND code = 'default')
WHERE dept_id IS NULL;

-- 4.10 审计 / 会话 / 消息
UPDATE ops_audit_log a
SET tenant_id = u.tenant_id, dept_id = u.dept_id
FROM ops_user u
WHERE a.username = u.username;

UPDATE ops_audit_log
SET dept_id = (SELECT id FROM ops_dept WHERE tenant_id = 't-default' AND code = 'default')
WHERE dept_id IS NULL;

UPDATE ops_message m
SET tenant_id = c.tenant_id
FROM ops_conversation c
WHERE m.session_id = c.session_id;

-- 4.11 向量库 metadata：只改 metadata，不重新 embedding
--      说明：Spring AI 的 vector_store.metadata 可能是 json 或 jsonb，
--            这里按实际类型选择写法，避免迁移失败。
DO $$
DECLARE
    metadata_type TEXT;
BEGIN
    IF to_regclass('vector_store') IS NULL THEN
        RETURN;
    END IF;

    SELECT format_type(a.atttypid, a.atttypmod)
      INTO metadata_type
      FROM pg_attribute a
     WHERE a.attrelid = to_regclass('vector_store')
       AND a.attname = 'metadata'
       AND a.attnum > 0
       AND NOT a.attisdropped;

    IF metadata_type = 'jsonb' THEN
        -- 上传文档：继承文档的租户/部门
        UPDATE vector_store v
        SET metadata = COALESCE(v.metadata, '{}'::jsonb) || jsonb_build_object(
            'tenantId',  d.tenant_id,
            'deptId',    COALESCE(d.dept_id::text, ''),
            'visibility','PERSONAL'
        )
        FROM knowledge_document d
        WHERE v.metadata->>'source' = 'upload:' || d.id
          AND (v.metadata->>'tenantId') IS NULL;

        -- 其余（内置 / 案例 / 孤儿上传）：内置归平台 PUBLIC，案例归引导租户 TENANT，
        -- 孤儿上传向量归引导租户 PERSONAL（绝不回填成 PUBLIC）
        UPDATE vector_store
        SET metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object(
            'tenantId',  CASE
                             WHEN metadata IS NULL THEN 't-default'
                             WHEN metadata->>'source' LIKE 'case:%'   THEN 't-default'
                             WHEN metadata->>'source' LIKE 'upload:%' THEN 't-default'
                             ELSE '0'
                         END,
            'deptId',    '',
            'visibility',CASE
                             WHEN metadata IS NULL THEN 'PERSONAL'
                             WHEN metadata->>'source' LIKE 'case:%'   THEN 'TENANT'
                             WHEN metadata->>'source' LIKE 'upload:%' THEN 'PERSONAL'
                             ELSE 'PUBLIC'
                         END
        )
        WHERE (metadata->>'tenantId') IS NULL;
    ELSE
        -- 上传文档：继承文档的租户/部门
        UPDATE vector_store v
        SET metadata = (COALESCE(v.metadata::jsonb, '{}'::jsonb) || jsonb_build_object(
            'tenantId',  d.tenant_id,
            'deptId',    COALESCE(d.dept_id::text, ''),
            'visibility','PERSONAL'
        ))::json
        FROM knowledge_document d
        WHERE v.metadata->>'source' = 'upload:' || d.id
          AND (v.metadata->>'tenantId') IS NULL;

        -- 其余（内置 / 案例 / 孤儿上传）：内置归平台 PUBLIC，案例归引导租户 TENANT，
        -- 孤儿上传向量归引导租户 PERSONAL（绝不回填成 PUBLIC）
        UPDATE vector_store
        SET metadata = (COALESCE(metadata::jsonb, '{}'::jsonb) || jsonb_build_object(
            'tenantId',  CASE
                             WHEN metadata IS NULL THEN 't-default'
                             WHEN metadata->>'source' LIKE 'case:%'   THEN 't-default'
                             WHEN metadata->>'source' LIKE 'upload:%' THEN 't-default'
                             ELSE '0'
                         END,
            'deptId',    '',
            'visibility',CASE
                             WHEN metadata IS NULL THEN 'PERSONAL'
                             WHEN metadata->>'source' LIKE 'case:%'   THEN 'TENANT'
                             WHEN metadata->>'source' LIKE 'upload:%' THEN 'PERSONAL'
                             ELSE 'PUBLIC'
                         END
        ))::json
        WHERE (metadata->>'tenantId') IS NULL;
    END IF;
END $$;

-- ============================================================
-- 四补、向量检索 metadata 回填（owner / reviewStatus）
-- ============================================================
DO $$
DECLARE
    metadata_type TEXT;
BEGIN
    SELECT format_type(a.atttypid, a.atttypmod)
    INTO metadata_type
    FROM pg_attribute a
    JOIN pg_class c ON c.oid = a.attrelid
    WHERE c.relname = 'vector_store' AND a.attname = 'metadata';

    IF metadata_type = 'jsonb' THEN
        -- 上传文档：补齐作者与审核状态，供 PERSONAL / DEPT / TENANT 检索过滤
        UPDATE vector_store v
        SET metadata = COALESCE(v.metadata, '{}'::jsonb) || jsonb_build_object(
            'owner',        COALESCE(d.owner, ''),
            'reviewStatus', COALESCE(d.review_status, 'APPROVED')
        )
        FROM knowledge_document d
        WHERE v.metadata->>'source' = 'upload:' || d.id
          AND (v.metadata->>'owner') IS NULL;

        -- 内置 / 案例 / 孤儿向量：统一补平台/系统默认值
        UPDATE vector_store
        SET metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object(
            'owner',        'system',
            'reviewStatus', 'APPROVED'
        )
        WHERE metadata->>'owner' IS NULL;
    ELSE
        UPDATE vector_store v
        SET metadata = (COALESCE(v.metadata::jsonb, '{}'::jsonb) || jsonb_build_object(
            'owner',        COALESCE(d.owner, ''),
            'reviewStatus', COALESCE(d.review_status, 'APPROVED')
        ))::json
        FROM knowledge_document d
        WHERE v.metadata->>'source' = 'upload:' || d.id
          AND (v.metadata->>'owner') IS NULL;

        UPDATE vector_store
        SET metadata = (COALESCE(metadata::jsonb, '{}'::jsonb) || jsonb_build_object(
            'owner',        'system',
            'reviewStatus', 'APPROVED'
        ))::json
        WHERE metadata->>'owner' IS NULL;
    END IF;
END $$;

-- ============================================================
-- 四补-2：案例/内置知识向量 metadata 最终一致性
-- ============================================================
DO $$
DECLARE
    metadata_type TEXT;
BEGIN
    SELECT format_type(a.atttypid, a.atttypmod)
    INTO metadata_type
    FROM pg_attribute a
    JOIN pg_class c ON c.oid = a.attrelid
    WHERE c.relname = 'vector_store' AND a.attname = 'metadata';

    IF metadata_type = 'jsonb' THEN
        -- 案例知识：从 knowledge_document（layer=CASE）继承租户/部门/可见性/审核/owner
        UPDATE vector_store v
        SET metadata = COALESCE(v.metadata, '{}'::jsonb) || jsonb_build_object(
            'tenantId',     COALESCE(d.tenant_id, 't-default'),
            'deptId',       COALESCE(d.dept_id::text, ''),
            'visibility',   COALESCE(d.visibility, 'TENANT'),
            'reviewStatus', COALESCE(d.review_status, 'APPROVED'),
            'owner',        COALESCE(d.owner, 'system')
        )
        FROM knowledge_document d
        WHERE v.metadata->>'source' = d.source
          AND d.layer = 'CASE';

        -- 内置知识：平台公共知识
        UPDATE vector_store
        SET metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object(
            'tenantId',     '0',
            'deptId',       '',
            'visibility',   'PUBLIC',
            'reviewStatus', 'APPROVED',
            'owner',        'system',
            'layer',        'BUILTIN'
        )
        WHERE COALESCE(metadata->>'source', '') <> ''
          AND COALESCE(metadata->>'source', '') NOT LIKE 'upload:%'
          AND COALESCE(metadata->>'source', '') NOT LIKE 'case:%';
    ELSE
        UPDATE vector_store v
        SET metadata = (COALESCE(v.metadata::jsonb, '{}'::jsonb) || jsonb_build_object(
            'tenantId',     COALESCE(d.tenant_id, 't-default'),
            'deptId',       COALESCE(d.dept_id::text, ''),
            'visibility',   COALESCE(d.visibility, 'TENANT'),
            'reviewStatus', COALESCE(d.review_status, 'APPROVED'),
            'owner',        COALESCE(d.owner, 'system')
        ))::json
        FROM knowledge_document d
        WHERE v.metadata->>'source' = d.source
          AND d.layer = 'CASE';

        UPDATE vector_store
        SET metadata = (COALESCE(metadata::jsonb, '{}'::jsonb) || jsonb_build_object(
            'tenantId',     '0',
            'deptId',       '',
            'visibility',   'PUBLIC',
            'reviewStatus', 'APPROVED',
            'owner',        'system',
            'layer',        'BUILTIN'
        ))::json
        WHERE COALESCE(metadata->>'source', '') <> ''
          AND COALESCE(metadata->>'source', '') NOT LIKE 'upload:%'
          AND COALESCE(metadata->>'source', '') NOT LIKE 'case:%';
    END IF;
END $$;

-- 兜底：兼容“列已存在但可空”的重复执行场景，确保 NOT NULL 不会失败
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'ops_user', 'ops_service', 'metric_sample', 'alert_rule', 'alert_event',
        'user_alert_read', 'alert_rule_escalation_level', 'alert_escalation',
        'notification_channel', 'notification_record', 'failure_snapshot',
        'diagnosis_record', 'knowledge_document', 'knowledge_chunk', 'knowledge_case',
        'knowledge_hit_log', 'knowledge_conflict', 'knowledge_document_version',
        'maintenance_window', 'oncall_member', 'ops_audit_log', 'ops_conversation', 'ops_message'
    ] LOOP
        EXECUTE format('UPDATE %I SET tenant_id = ''t-default'' WHERE tenant_id IS NULL', t);
    END LOOP;
END $$;

-- 知识字段兜底
UPDATE knowledge_document SET visibility = 'PERSONAL' WHERE visibility IS NULL;
UPDATE knowledge_document SET review_status = 'DRAFT' WHERE review_status IS NULL;
UPDATE knowledge_document SET review_level = 'NONE' WHERE review_level IS NULL;
UPDATE knowledge_case SET visibility = 'TENANT' WHERE visibility IS NULL;

ALTER TABLE ops_user                     ALTER COLUMN tenant_id SET DEFAULT 't-default';
ALTER TABLE ops_service                  ALTER COLUMN tenant_id SET DEFAULT 't-default';
ALTER TABLE metric_sample                ALTER COLUMN tenant_id SET DEFAULT 't-default';
ALTER TABLE alert_rule                   ALTER COLUMN tenant_id SET DEFAULT 't-default';
ALTER TABLE alert_event                  ALTER COLUMN tenant_id SET DEFAULT 't-default';
ALTER TABLE user_alert_read              ALTER COLUMN tenant_id SET DEFAULT 't-default';
ALTER TABLE alert_rule_escalation_level  ALTER COLUMN tenant_id SET DEFAULT 't-default';
ALTER TABLE alert_escalation             ALTER COLUMN tenant_id SET DEFAULT 't-default';
ALTER TABLE notification_channel         ALTER COLUMN tenant_id SET DEFAULT 't-default';
ALTER TABLE notification_record          ALTER COLUMN tenant_id SET DEFAULT 't-default';
ALTER TABLE failure_snapshot             ALTER COLUMN tenant_id SET DEFAULT 't-default';
ALTER TABLE diagnosis_record             ALTER COLUMN tenant_id SET DEFAULT 't-default';
ALTER TABLE knowledge_document           ALTER COLUMN tenant_id SET DEFAULT 't-default';
ALTER TABLE knowledge_document           ALTER COLUMN visibility SET DEFAULT 'PERSONAL';
ALTER TABLE knowledge_document           ALTER COLUMN review_status SET DEFAULT 'DRAFT';
ALTER TABLE knowledge_document           ALTER COLUMN review_level SET DEFAULT 'NONE';
ALTER TABLE knowledge_chunk              ALTER COLUMN tenant_id SET DEFAULT 't-default';
ALTER TABLE knowledge_case               ALTER COLUMN tenant_id SET DEFAULT 't-default';
ALTER TABLE knowledge_case               ALTER COLUMN visibility SET DEFAULT 'TENANT';
ALTER TABLE knowledge_hit_log            ALTER COLUMN tenant_id SET DEFAULT 't-default';
ALTER TABLE knowledge_conflict           ALTER COLUMN tenant_id SET DEFAULT 't-default';
ALTER TABLE knowledge_document_version   ALTER COLUMN tenant_id SET DEFAULT 't-default';
ALTER TABLE maintenance_window           ALTER COLUMN tenant_id SET DEFAULT 't-default';
ALTER TABLE oncall_member                ALTER COLUMN tenant_id SET DEFAULT 't-default';
ALTER TABLE ops_audit_log                ALTER COLUMN tenant_id SET DEFAULT 't-default';
ALTER TABLE ops_conversation             ALTER COLUMN tenant_id SET DEFAULT 't-default';
ALTER TABLE ops_message                  ALTER COLUMN tenant_id SET DEFAULT 't-default';

ALTER TABLE ops_user                     ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE ops_service                  ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE metric_sample                ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE alert_rule                   ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE alert_event                  ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE user_alert_read              ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE alert_rule_escalation_level  ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE alert_escalation             ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE notification_channel         ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE notification_record          ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE failure_snapshot             ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE diagnosis_record             ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE knowledge_document           ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE knowledge_document           ALTER COLUMN visibility SET NOT NULL;
ALTER TABLE knowledge_document           ALTER COLUMN review_status SET NOT NULL;
ALTER TABLE knowledge_document           ALTER COLUMN review_level SET NOT NULL;
ALTER TABLE knowledge_chunk              ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE knowledge_case               ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE knowledge_case               ALTER COLUMN visibility SET NOT NULL;
ALTER TABLE knowledge_hit_log            ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE knowledge_conflict           ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE knowledge_document_version   ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE maintenance_window           ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE oncall_member                ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE ops_audit_log                ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE ops_conversation             ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE ops_message                  ALTER COLUMN tenant_id SET NOT NULL;

-- ============================================================
-- 六、唯一约束迁移（租户内唯一）
-- ============================================================

-- 6.1 服务名：全局唯一 -> 租户内唯一
ALTER TABLE ops_service DROP CONSTRAINT IF EXISTS ops_service_name_key;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_service_tenant_name' AND conrelid = 'ops_service'::regclass) THEN
        ALTER TABLE ops_service ADD CONSTRAINT uk_service_tenant_name UNIQUE (tenant_id, name);
    END IF;
END $$;

-- 6.2 用户名：全局唯一 -> 租户内唯一（登录改走邮箱身份）
ALTER TABLE ops_user DROP CONSTRAINT IF EXISTS ops_user_username_key;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_user_tenant_username' AND conrelid = 'ops_user'::regclass) THEN
        ALTER TABLE ops_user ADD CONSTRAINT uk_user_tenant_username UNIQUE (tenant_id, username);
    END IF;
END $$;

-- 6.3 告警规则名：租户内唯一
ALTER TABLE alert_rule DROP CONSTRAINT IF EXISTS alert_rule_name_key;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_alert_rule_tenant_name' AND conrelid = 'alert_rule'::regclass) THEN
        ALTER TABLE alert_rule ADD CONSTRAINT uk_alert_rule_tenant_name UNIQUE (tenant_id, name);
    END IF;
END $$;

-- 6.4 通知渠道名：租户内唯一
ALTER TABLE notification_channel DROP CONSTRAINT IF EXISTS notification_channel_name_key;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_notification_channel_tenant_name' AND conrelid = 'notification_channel'::regclass) THEN
        ALTER TABLE notification_channel ADD CONSTRAINT uk_notification_channel_tenant_name UNIQUE (tenant_id, name);
    END IF;
END $$;

-- 6.5 会话 session_id：租户内唯一
ALTER TABLE ops_conversation DROP CONSTRAINT IF EXISTS ops_conversation_session_id_key;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_conversation_tenant_session' AND conrelid = 'ops_conversation'::regclass) THEN
        ALTER TABLE ops_conversation ADD CONSTRAINT uk_conversation_tenant_session UNIQUE (tenant_id, session_id);
    END IF;
END $$;

-- 6.6 告警已读：主键加入 tenant_id
ALTER TABLE user_alert_read DROP CONSTRAINT IF EXISTS user_alert_read_pkey;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'pk_user_alert_read' AND conrelid = 'user_alert_read'::regclass) THEN
        ALTER TABLE user_alert_read ADD CONSTRAINT pk_user_alert_read PRIMARY KEY (tenant_id, user_id, alert_event_id);
    END IF;
END $$;

-- 6.7 升级级别：唯一键加入 tenant_id（rule_id 本身全局唯一，加租户为一致性）
ALTER TABLE alert_rule_escalation_level DROP CONSTRAINT IF EXISTS uk_rule_level;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_rule_level_tenant' AND conrelid = 'alert_rule_escalation_level'::regclass) THEN
        ALTER TABLE alert_rule_escalation_level ADD CONSTRAINT uk_rule_level_tenant UNIQUE (tenant_id, rule_id, level_no);
    END IF;
END $$;

-- ============================================================
-- 七、索引（租户/部门/可见性查询）
-- ============================================================

CREATE INDEX IF NOT EXISTS idx_user_tenant_dept              ON ops_user(tenant_id, dept_id);
CREATE INDEX IF NOT EXISTS idx_service_tenant_dept           ON ops_service(tenant_id, dept_id);
CREATE INDEX IF NOT EXISTS idx_metric_sample_tenant          ON metric_sample(tenant_id, sampled_at);
CREATE INDEX IF NOT EXISTS idx_alert_rule_tenant_dept        ON alert_rule(tenant_id, dept_id);
CREATE INDEX IF NOT EXISTS idx_alert_event_tenant_dept       ON alert_event(tenant_id, dept_id, fired_at);
CREATE INDEX IF NOT EXISTS idx_user_alert_read_tenant        ON user_alert_read(tenant_id, user_id);
CREATE INDEX IF NOT EXISTS idx_escalation_level_tenant       ON alert_rule_escalation_level(tenant_id, rule_id);
CREATE INDEX IF NOT EXISTS idx_alert_escalation_tenant       ON alert_escalation(tenant_id, alert_event_id);
CREATE INDEX IF NOT EXISTS idx_notification_channel_tenant   ON notification_channel(tenant_id);
CREATE INDEX IF NOT EXISTS idx_notification_record_tenant    ON notification_record(tenant_id, dept_id, created_at);
CREATE INDEX IF NOT EXISTS idx_failure_snapshot_tenant       ON failure_snapshot(tenant_id, dept_id, created_at);
CREATE INDEX IF NOT EXISTS idx_diagnosis_tenant_dept         ON diagnosis_record(tenant_id, dept_id, created_at);
CREATE INDEX IF NOT EXISTS idx_knowledge_doc_tenant_dept     ON knowledge_document(tenant_id, dept_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_doc_visibility      ON knowledge_document(tenant_id, visibility, review_status);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_tenant        ON knowledge_chunk(tenant_id, doc_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_case_tenant         ON knowledge_case(tenant_id, dept_id, status);
CREATE INDEX IF NOT EXISTS idx_knowledge_hit_tenant          ON knowledge_hit_log(tenant_id, dept_id, created_at);
CREATE INDEX IF NOT EXISTS idx_knowledge_conflict_tenant     ON knowledge_conflict(tenant_id, dept_id, status);
CREATE INDEX IF NOT EXISTS idx_knowledge_version_tenant      ON knowledge_document_version(tenant_id, doc_id);
CREATE INDEX IF NOT EXISTS idx_maintenance_tenant            ON maintenance_window(tenant_id, dept_id, until);
CREATE INDEX IF NOT EXISTS idx_oncall_tenant                 ON oncall_member(tenant_id, dept_id);
CREATE INDEX IF NOT EXISTS idx_audit_tenant                  ON ops_audit_log(tenant_id, dept_id, created_at);
CREATE INDEX IF NOT EXISTS idx_conversation_tenant           ON ops_conversation(tenant_id, last_active_at DESC);
CREATE INDEX IF NOT EXISTS idx_message_tenant                ON ops_message(tenant_id, session_id, created_at);

-- ============================================================
-- 八、字段说明（核心字段注释）
-- ============================================================

COMMENT ON COLUMN ops_user.tenant_id                     IS '所属租户；平台管理员可为 0';
COMMENT ON COLUMN ops_user.dept_id                       IS '所属部门；平台管理员可为空';
COMMENT ON COLUMN ops_user.role                          IS '历史角色字段（ADMIN/OPERATOR/VIEWER），新权限模型以 ops_user_role 为准';
COMMENT ON COLUMN ops_service.tenant_id                  IS '所属租户';
COMMENT ON COLUMN ops_service.dept_id                    IS '归属部门；为空表示待分配';
COMMENT ON COLUMN alert_rule.tenant_id                   IS '所属租户';
COMMENT ON COLUMN alert_rule.dept_id                     IS '规则归属部门';
COMMENT ON COLUMN alert_event.tenant_id                  IS '所属租户（冗余自规则，便于查询）';
COMMENT ON COLUMN alert_event.dept_id                    IS '归属部门（冗余自规则，便于查询）';
COMMENT ON COLUMN knowledge_document.tenant_id           IS '所属租户；0 表示平台公共知识';
COMMENT ON COLUMN knowledge_document.dept_id             IS '归属部门；PUBLIC/平台知识可为空';
COMMENT ON COLUMN knowledge_document.visibility          IS '可见性：PERSONAL/DEPT/TENANT/PUBLIC';
COMMENT ON COLUMN knowledge_document.review_status       IS '审核状态：DRAFT/PENDING_DEPT/PENDING_TENANT/APPROVED/REJECTED';
COMMENT ON COLUMN knowledge_document.review_level        IS '当前审核层级：NONE/DEPT/TENANT';
COMMENT ON COLUMN knowledge_document.reviewed_by         IS '最近审核人';
COMMENT ON COLUMN knowledge_document.reviewed_at         IS '最近审核时间';
COMMENT ON COLUMN knowledge_document.review_note         IS '审核意见/驳回原因';
COMMENT ON COLUMN knowledge_case.tenant_id               IS '所属租户';
COMMENT ON COLUMN knowledge_case.visibility              IS '案例可见性；确认后默认 TENANT';
COMMENT ON COLUMN ops_conversation.tenant_id             IS '所属租户';
COMMENT ON COLUMN ops_message.tenant_id                  IS '所属租户（冗余自会话，便于隔离）';
COMMENT ON COLUMN ops_audit_log.tenant_id                IS '所属租户';
COMMENT ON COLUMN ops_audit_log.dept_id                  IS '操作人所属部门';

-- 完成
