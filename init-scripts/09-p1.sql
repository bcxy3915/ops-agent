-- =====================================================
-- P1 变更：诊断耗时/引用溯源 / 维护窗口 / 值班管理
-- 幂等：IF NOT EXISTS / ADD COLUMN IF NOT EXISTS，可重复执行
-- =====================================================

-- -----------------------------------------------------
-- 1. diagnosis_record：诊断耗时（summary avgDiagnoseMs）与引用来源（参考知识溯源）
-- -----------------------------------------------------
ALTER TABLE diagnosis_record ADD COLUMN IF NOT EXISTS duration_ms   BIGINT;
ALTER TABLE diagnosis_record ADD COLUMN IF NOT EXISTS references_json TEXT;

-- -----------------------------------------------------
-- 2. 维护窗口表（服务维度，P1 简版）
--    service 单服务一行；reason 与单条静默一致；ended_at 非空表示已提前结束
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS maintenance_window (
    id          BIGSERIAL PRIMARY KEY,
    service     VARCHAR(128) NOT NULL,
    reason      VARCHAR(256) NOT NULL,
    until       TIMESTAMP    NOT NULL,
    note        VARCHAR(500),
    created_by  VARCHAR(64),
    created_at  TIMESTAMP DEFAULT NOW(),
    ended_at    TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_maintenance_window_service ON maintenance_window(service);
CREATE INDEX IF NOT EXISTS idx_maintenance_window_active ON maintenance_window(until, ended_at);

-- -----------------------------------------------------
-- 3. 值班人表（全局主/备，P1 极简版）
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS oncall_member (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    duty_role  VARCHAR(16) NOT NULL,   -- PRIMARY / BACKUP
    added_by   VARCHAR(64),
    added_at   TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_oncall_member_role ON oncall_member(duty_role);
