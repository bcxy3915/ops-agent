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