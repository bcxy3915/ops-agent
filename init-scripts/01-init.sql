-- pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;

-- 服务注册表
CREATE TABLE IF NOT EXISTS ops_service (
    id              VARCHAR(32) PRIMARY KEY,
    name            VARCHAR(128) NOT NULL UNIQUE,
    base_url        VARCHAR(255) NOT NULL,
    health_path     VARCHAR(128) DEFAULT '/actuator/health',
    metrics_path    VARCHAR(128) DEFAULT '/actuator/metrics',
    owner           VARCHAR(64),
    env             VARCHAR(32) DEFAULT 'dev',
    tags            JSONB,
    auth_token      VARCHAR(255),
    status          VARCHAR(16) DEFAULT 'UNKNOWN',
    last_checked_at TIMESTAMP,
    registered_at   TIMESTAMP,
    updated_at      TIMESTAMP
    );

CREATE INDEX IF NOT EXISTS idx_ops_service_name ON ops_service(name);
CREATE INDEX IF NOT EXISTS idx_ops_service_env ON ops_service(env);
CREATE INDEX IF NOT EXISTS idx_ops_service_status ON ops_service(status);



-- =====================================================
-- 用户表（P4 新增）
-- =====================================================
CREATE TABLE IF NOT EXISTS ops_user (
    id              VARCHAR(32) PRIMARY KEY,
    username        VARCHAR(64) NOT NULL UNIQUE,
    password        VARCHAR(128) NOT NULL,
    role            VARCHAR(32) NOT NULL,
    enabled         BOOLEAN DEFAULT TRUE,
    phone           VARCHAR(32),
    email           VARCHAR(128),
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
    );

CREATE INDEX IF NOT EXISTS idx_ops_user_username ON ops_user(username);


-- =====================================================
-- 审计日志表（P4-3 新增）
-- =====================================================
CREATE TABLE IF NOT EXISTS ops_audit_log (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(64),
    operation       VARCHAR(64) NOT NULL,
    target          VARCHAR(255),
    method          VARCHAR(16),
    uri             VARCHAR(255),
    params          TEXT,
    result          VARCHAR(16) NOT NULL,      -- SUCCESS / FAILURE
    error_message   TEXT,
    ip              VARCHAR(64),
    user_agent      VARCHAR(512),
    duration_ms     BIGINT,
    created_at      TIMESTAMP DEFAULT NOW()
    );

CREATE INDEX IF NOT EXISTS idx_audit_username ON ops_audit_log(username);
CREATE INDEX IF NOT EXISTS idx_audit_operation ON ops_audit_log(operation);
CREATE INDEX IF NOT EXISTS idx_audit_created_at ON ops_audit_log(created_at);
CREATE INDEX IF NOT EXISTS idx_audit_result ON ops_audit_log(result);

