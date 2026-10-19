-- =====================================================
-- 初始化内置账号（admin / operator / viewer）
-- 密码均为 BCrypt 加密（由 PasswordGenTest 生成）
-- 幂等：用户名已存在时跳过，重复执行不报错
-- 说明：本目录脚本由 docker-compose 挂载到 PostgreSQL
--       /docker-entrypoint-initdb.d，仅在数据库数据卷首次初始化时执行
-- =====================================================
INSERT INTO ops_user (id, username, password, role, enabled, created_at, updated_at)
VALUES
    ('a0000000000000000000000000000001', 'admin',    '$2a$10$op5oglaY.2ycnIMLU.IXfO2OxhhOXeSbthTk/WZKUOvbgGw2ZcG3G', 'ADMIN',    TRUE, NOW(), NOW()),
    ('a0000000000000000000000000000002', 'operator', '$2a$10$T5OjiY4mCABGLr2EOooPketAtodLLj6bumuIOd33PdBR7WPz.Ltc6', 'OPERATOR', TRUE, NOW(), NOW()),
    ('a0000000000000000000000000000003', 'viewer',   '$2a$10$Tf9enKHm052cLcVT0GFEy.j0hSD2P4YDvwk5MX1y1evLhDCMKK6xe', 'VIEWER',   TRUE, NOW(), NOW())
ON CONFLICT (username) DO NOTHING;
