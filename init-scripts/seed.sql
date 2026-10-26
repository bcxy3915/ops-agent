-- ============================================================
-- Ops Agent 基础演示数据
-- 依赖：先执行 schema.sql
-- 密码说明：
--   admin / admin123
--   operator / operator123
--   viewer / viewer123
--   zhangwei / lina / wangqiang / chenchen / yunfan / beichen 均使用 ops123456
-- ============================================================

BEGIN;

-- ------------------------------------------------------------
-- 1. 租户（平台 + 三家企业）
-- ------------------------------------------------------------
INSERT INTO ops_tenant (id, code, name, status, knowledge_review_mode, review_four_eyes, created_at, updated_at)
VALUES
    ('0',          'PLATFORM', '平台',     'ACTIVE', 'DEPT_ONLY',       TRUE, NOW(), NOW()),
    ('t-xinghai',  'XH',       '星海科技', 'ACTIVE', 'DEPT_AND_TENANT', TRUE, NOW(), NOW()),
    ('t-yunfan',   'YF',       '云帆物流', 'ACTIVE', 'DEPT_ONLY',       TRUE, NOW(), NOW()),
    ('t-beichen',  'BC',       '北辰制造', 'ACTIVE', 'DEPT_AND_TENANT', TRUE, NOW(), NOW())
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------
-- 2. 部门树
-- ------------------------------------------------------------
INSERT INTO ops_dept (id, tenant_id, parent_id, code, name, path, level, sort, status, created_at, updated_at)
VALUES
    (1,  't-xinghai', NULL, 'HQ',       '总部',       '/1/',       1, 1, 'ACTIVE', NOW(), NOW()),
    (2,  't-xinghai', 1,    'OPS1',     '运维一部',   '/1/2/',     2, 1, 'ACTIVE', NOW(), NOW()),
    (3,  't-xinghai', 2,    'INFRA',    '基础架构组', '/1/2/3/',   3, 1, 'ACTIVE', NOW(), NOW()),
    (4,  't-xinghai', 2,    'APPOPS',   '应用运维组', '/1/2/4/',   3, 2, 'ACTIVE', NOW(), NOW()),
    (5,  't-xinghai', 1,    'OPS2',     '运维二部',   '/1/5/',     2, 2, 'ACTIVE', NOW(), NOW()),
    (6,  't-xinghai', 5,    'DATA',     '数据平台组', '/1/5/6/',   3, 1, 'ACTIVE', NOW(), NOW()),
    (7,  't-xinghai', 1,    'SEC',      '安全合规部', '/1/7/',     2, 3, 'ACTIVE', NOW(), NOW()),
    (8,  't-xinghai', 1,    'PLATFORM', '平台工程部', '/1/8/',     2, 4, 'ACTIVE', NOW(), NOW()),
    (9,  't-yunfan',  NULL, 'YF-HQ',    '运营总部',   '/9/',       1, 1, 'ACTIVE', NOW(), NOW()),
    (10, 't-beichen', NULL, 'BC-HQ',    '生产总部',   '/10/',      1, 1, 'ACTIVE', NOW(), NOW())
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------
-- 3. 角色与权限点
-- ------------------------------------------------------------
INSERT INTO ops_role (code, name, scope, builtin, created_at)
VALUES
    ('PLATFORM_ADMIN', '平台管理员', 'PLATFORM', TRUE, NOW()),
    ('TENANT_ADMIN',   '企业管理员', 'TENANT',   TRUE, NOW()),
    ('DEPT_ADMIN',     '部门管理员', 'DEPT',     TRUE, NOW()),
    ('CONTRIBUTOR',    '运维工程师', 'DEPT',     TRUE, NOW()),
    ('VIEWER',         '只读成员',   'DEPT',     TRUE, NOW())
ON CONFLICT (code) DO NOTHING;

INSERT INTO ops_permission (code, name, module)
VALUES
    ('tenant:manage',       '租户管理',      'tenant'),
    ('tenant:config',       '租户配置',      'tenant'),
    ('dept:manage',         '部门管理',      'org'),
    ('dept:read',           '部门查看',      'org'),
    ('user:manage',         '用户管理',      'org'),
    ('user:read',           '用户查看',      'org'),
    ('role:manage',         '角色管理',      'org'),
    ('knowledge:read',      '知识查看',      'knowledge'),
    ('knowledge:write',     '知识上传/编辑', 'knowledge'),
    ('knowledge:review',    '知识审核',      'knowledge'),
    ('knowledge:publish',   '知识发布',      'knowledge'),
    ('knowledge:delete',    '知识删除',      'knowledge'),
    ('knowledge:admin',     '知识管理',      'knowledge'),
    ('service:read',        '服务查看',      'service'),
    ('service:manage',      '服务管理',      'service'),
    ('alert:read',          '告警查看',      'alert'),
    ('alert:manage',        '告警规则管理',  'alert'),
    ('alert:ack',           '告警认领/确认', 'alert'),
    ('alert:resolve',       '告警恢复',      'alert'),
    ('alert:silence',       '告警静默',      'alert'),
    ('diagnosis:read',      '诊断查看',      'diagnosis'),
    ('diagnosis:trigger',   '诊断触发',      'diagnosis'),
    ('notification:manage', '通知渠道管理',  'notification'),
    ('maintenance:manage',  '维护窗口管理',  'maintenance'),
    ('oncall:manage',       '值班管理',      'oncall'),
    ('audit:read',          '审计查看',      'audit')
ON CONFLICT (code) DO NOTHING;

-- 角色 → 权限点
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM (VALUES
    ('PLATFORM_ADMIN', 'tenant:manage'),
    ('PLATFORM_ADMIN', 'tenant:config'),
    ('PLATFORM_ADMIN', 'role:manage'),
    ('PLATFORM_ADMIN', 'knowledge:read'),
    ('PLATFORM_ADMIN', 'service:read'),
    ('PLATFORM_ADMIN', 'alert:read'),
    ('PLATFORM_ADMIN', 'diagnosis:read'),
    ('PLATFORM_ADMIN', 'user:read'),
    ('PLATFORM_ADMIN', 'audit:read'),

    ('TENANT_ADMIN', 'dept:manage'),
    ('TENANT_ADMIN', 'dept:read'),
    ('TENANT_ADMIN', 'user:manage'),
    ('TENANT_ADMIN', 'user:read'),
    ('TENANT_ADMIN', 'knowledge:read'),
    ('TENANT_ADMIN', 'knowledge:write'),
    ('TENANT_ADMIN', 'knowledge:review'),
    ('TENANT_ADMIN', 'knowledge:publish'),
    ('TENANT_ADMIN', 'knowledge:delete'),
    ('TENANT_ADMIN', 'knowledge:admin'),
    ('TENANT_ADMIN', 'service:read'),
    ('TENANT_ADMIN', 'service:manage'),
    ('TENANT_ADMIN', 'alert:read'),
    ('TENANT_ADMIN', 'alert:manage'),
    ('TENANT_ADMIN', 'alert:ack'),
    ('TENANT_ADMIN', 'alert:resolve'),
    ('TENANT_ADMIN', 'alert:silence'),
    ('TENANT_ADMIN', 'diagnosis:read'),
    ('TENANT_ADMIN', 'diagnosis:trigger'),
    ('TENANT_ADMIN', 'notification:manage'),
    ('TENANT_ADMIN', 'maintenance:manage'),
    ('TENANT_ADMIN', 'oncall:manage'),
    ('TENANT_ADMIN', 'audit:read'),

    ('DEPT_ADMIN', 'dept:read'),
    ('DEPT_ADMIN', 'user:manage'),
    ('DEPT_ADMIN', 'user:read'),
    ('DEPT_ADMIN', 'knowledge:read'),
    ('DEPT_ADMIN', 'knowledge:write'),
    ('DEPT_ADMIN', 'knowledge:review'),
    ('DEPT_ADMIN', 'knowledge:publish'),
    ('DEPT_ADMIN', 'service:read'),
    ('DEPT_ADMIN', 'alert:read'),
    ('DEPT_ADMIN', 'alert:ack'),
    ('DEPT_ADMIN', 'alert:resolve'),
    ('DEPT_ADMIN', 'alert:silence'),
    ('DEPT_ADMIN', 'diagnosis:read'),
    ('DEPT_ADMIN', 'diagnosis:trigger'),
    ('DEPT_ADMIN', 'maintenance:manage'),
    ('DEPT_ADMIN', 'oncall:manage'),

    ('CONTRIBUTOR', 'knowledge:read'),
    ('CONTRIBUTOR', 'knowledge:write'),
    ('CONTRIBUTOR', 'service:read'),
    ('CONTRIBUTOR', 'alert:read'),
    ('CONTRIBUTOR', 'alert:ack'),
    ('CONTRIBUTOR', 'alert:resolve'),
    ('CONTRIBUTOR', 'alert:silence'),
    ('CONTRIBUTOR', 'diagnosis:read'),
    ('CONTRIBUTOR', 'diagnosis:trigger'),

    ('VIEWER', 'knowledge:read'),
    ('VIEWER', 'service:read'),
    ('VIEWER', 'alert:read'),
    ('VIEWER', 'diagnosis:read')
) AS m(role_code, perm_code)
JOIN ops_role r ON r.code = m.role_code
JOIN ops_permission p ON p.code = m.perm_code
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------
-- 4. 用户
-- ------------------------------------------------------------
INSERT INTO ops_user (id, username, password, role, enabled, display_name, tenant_id, dept_id, created_at, updated_at)
VALUES
    ('u-admin',     'admin',     '$2a$10$op5oglaY.2ycnIMLU.IXfO2OxhhOXeSbthTk/WZKUOvbgGw2ZcG3G', 'ADMIN',    TRUE, '管理员',     '0',         NULL, NOW(), NOW()),
    ('u-zhangwei',  'zhangwei',  '$2a$10$4c2cPaxpxfMz15GGd5Ma2.v8k7vyRGGJZLxXZqq.qvAv5131DaEF2', 'ADMIN',    TRUE, '张伟',       't-xinghai', 2,    NOW(), NOW()),
    ('u-lina',      'lina',      '$2a$10$4c2cPaxpxfMz15GGd5Ma2.v8k7vyRGGJZLxXZqq.qvAv5131DaEF2', 'OPERATOR', TRUE, '李娜',       't-xinghai', 2,    NOW(), NOW()),
    ('u-wangqiang', 'wangqiang', '$2a$10$4c2cPaxpxfMz15GGd5Ma2.v8k7vyRGGJZLxXZqq.qvAv5131DaEF2', 'OPERATOR', TRUE, '王强',       't-xinghai', 3,    NOW(), NOW()),
    ('u-chenchen',  'chenchen',  '$2a$10$4c2cPaxpxfMz15GGd5Ma2.v8k7vyRGGJZLxXZqq.qvAv5131DaEF2', 'VIEWER',   TRUE, '陈晨',       't-xinghai', 4,    NOW(), NOW()),
    ('u-operator',  'operator',  '$2a$10$T5OjiY4mCABGLr2EOooPketAtodLLj6bumuIOd33PdBR7WPz.Ltc6', 'OPERATOR', TRUE, '运维工程师', 't-xinghai', 2,    NOW(), NOW()),
    ('u-viewer',    'viewer',    '$2a$10$Tf9enKHm052cLcVT0GFEy.j0hSD2P4YDvwk5MX1y1evLhDCMKK6xe', 'VIEWER',   TRUE, '只读成员',   't-xinghai', 4,    NOW(), NOW()),
    ('u-yunfan',    'yunfan',    '$2a$10$4c2cPaxpxfMz15GGd5Ma2.v8k7vyRGGJZLxXZqq.qvAv5131DaEF2', 'ADMIN',    TRUE, '赵敏',       't-yunfan',  9,    NOW(), NOW()),
    ('u-beichen',   'beichen',   '$2a$10$4c2cPaxpxfMz15GGd5Ma2.v8k7vyRGGJZLxXZqq.qvAv5131DaEF2', 'ADMIN',    TRUE, '孙磊',       't-beichen', 10,   NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 登录身份（LOCAL：邮箱/账号 + 密码）
INSERT INTO ops_user_identity (user_id, provider, identifier, credential_hash, need_bind_email, status, last_login_at, created_at)
VALUES
    ('u-admin',     'LOCAL', 'admin@local',          '$2a$10$op5oglaY.2ycnIMLU.IXfO2OxhhOXeSbthTk/WZKUOvbgGw2ZcG3G', TRUE,  'ACTIVE', NULL, NOW()),
    ('u-zhangwei',  'LOCAL', 'zhangwei@xinghai.com', '$2a$10$4c2cPaxpxfMz15GGd5Ma2.v8k7vyRGGJZLxXZqq.qvAv5131DaEF2', FALSE, 'ACTIVE', NULL, NOW()),
    ('u-lina',      'LOCAL', 'lina@xinghai.com',     '$2a$10$4c2cPaxpxfMz15GGd5Ma2.v8k7vyRGGJZLxXZqq.qvAv5131DaEF2', FALSE, 'ACTIVE', NULL, NOW()),
    ('u-wangqiang', 'LOCAL', 'wangqiang@xinghai.com','$2a$10$4c2cPaxpxfMz15GGd5Ma2.v8k7vyRGGJZLxXZqq.qvAv5131DaEF2', FALSE, 'ACTIVE', NULL, NOW()),
    ('u-chenchen',  'LOCAL', 'chenchen@xinghai.com', '$2a$10$4c2cPaxpxfMz15GGd5Ma2.v8k7vyRGGJZLxXZqq.qvAv5131DaEF2', FALSE, 'ACTIVE', NULL, NOW()),
    ('u-operator',  'LOCAL', 'operator@local',       '$2a$10$T5OjiY4mCABGLr2EOooPketAtodLLj6bumuIOd33PdBR7WPz.Ltc6', TRUE,  'ACTIVE', NULL, NOW()),
    ('u-viewer',    'LOCAL', 'viewer@local',         '$2a$10$Tf9enKHm052cLcVT0GFEy.j0hSD2P4YDvwk5MX1y1evLhDCMKK6xe', TRUE,  'ACTIVE', NULL, NOW()),
    ('u-yunfan',    'LOCAL', 'yunfan@yunfan.com',    '$2a$10$4c2cPaxpxfMz15GGd5Ma2.v8k7vyRGGJZLxXZqq.qvAv5131DaEF2', FALSE, 'ACTIVE', NULL, NOW()),
    ('u-beichen',   'LOCAL', 'beichen@beichen.com',  '$2a$10$4c2cPaxpxfMz15GGd5Ma2.v8k7vyRGGJZLxXZqq.qvAv5131DaEF2', FALSE, 'ACTIVE', NULL, NOW())
ON CONFLICT DO NOTHING;

-- 用户 → 角色
INSERT INTO ops_user_role (user_id, role_id)
SELECT u.id, r.id
FROM (VALUES
    ('u-admin',     'PLATFORM_ADMIN'),
    ('u-zhangwei',  'TENANT_ADMIN'),
    ('u-lina',      'DEPT_ADMIN'),
    ('u-wangqiang', 'CONTRIBUTOR'),
    ('u-chenchen',  'VIEWER'),
    ('u-operator',  'CONTRIBUTOR'),
    ('u-viewer',    'VIEWER'),
    ('u-yunfan',    'TENANT_ADMIN'),
    ('u-beichen',   'TENANT_ADMIN')
) AS m(user_id, role_code)
JOIN ops_user u ON u.id = m.user_id
JOIN ops_role r ON r.code = m.role_code
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------
-- 5. 示例服务
-- ------------------------------------------------------------
INSERT INTO ops_service (id, name, base_url, health_path, metrics_path, owner, env, tags, status, last_checked_at, registered_at, updated_at, tenant_id, dept_id)
VALUES
    ('svc-todo',    'todo-service',    'http://todo-service:8080',    '/actuator/health', '/actuator/metrics', '张伟', 'prod', '{"team":"ops","language":"java"}'::jsonb, 'UP',       NOW(), NOW(), NOW(), 't-xinghai', 2),
    ('svc-order',   'order-service',   'http://order-service:8080',   '/actuator/health', '/actuator/metrics', '李娜', 'prod', '{"team":"ops","language":"java"}'::jsonb, 'UP',       NOW(), NOW(), NOW(), 't-xinghai', 4),
    ('svc-payment', 'payment-gateway', 'http://payment-gateway:8080', '/actuator/health', '/actuator/metrics', '王强', 'prod', '{"team":"pay","language":"go"}'::jsonb,   'DOWN',     NOW(), NOW(), NOW(), 't-xinghai', 4),
    ('svc-order-db','order-db',        'http://order-db:8080',        '/actuator/health', '/actuator/metrics', '张伟', 'prod', '{"team":"dba","language":"sql"}'::jsonb,  'DEGRADED', NOW(), NOW(), NOW(), 't-xinghai', 2)
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------
-- 6. 修正序列（显式插入 ID 后必须推进序列）
-- ------------------------------------------------------------
SELECT setval(pg_get_serial_sequence('ops_dept', 'id'), COALESCE((SELECT MAX(id) FROM ops_dept), 1), TRUE);
SELECT setval(pg_get_serial_sequence('ops_role', 'id'), COALESCE((SELECT MAX(id) FROM ops_role), 1), TRUE);
SELECT setval(pg_get_serial_sequence('ops_permission', 'id'), COALESCE((SELECT MAX(id) FROM ops_permission), 1), TRUE);
SELECT setval(pg_get_serial_sequence('ops_user_identity', 'id'), COALESCE((SELECT MAX(id) FROM ops_user_identity), 1), TRUE);

COMMIT;
