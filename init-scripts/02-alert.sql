-- =====================================================
-- 指标采样落库（T03）
-- =====================================================
CREATE TABLE IF NOT EXISTS metric_sample (
    id          BIGSERIAL PRIMARY KEY,
    service     VARCHAR(128) NOT NULL,
    metric      VARCHAR(64)  NOT NULL,
    value       DOUBLE PRECISION NOT NULL,
    sampled_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_metric_sample_lookup ON metric_sample(service, metric, sampled_at);
CREATE INDEX IF NOT EXISTS idx_metric_sample_time ON metric_sample(sampled_at);

-- =====================================================
-- 告警规则（T05）
-- =====================================================
CREATE TABLE IF NOT EXISTS alert_rule (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(128) NOT NULL UNIQUE,
    description       VARCHAR(512),
    service           VARCHAR(128),
    metric            VARCHAR(64)  NOT NULL,
    operator          VARCHAR(8)   NOT NULL,               -- gt / lt
    threshold         DOUBLE PRECISION NOT NULL,
    compare_mode      VARCHAR(16)  DEFAULT 'THRESHOLD',    -- THRESHOLD / YOY / SIGMA
    duration_seconds  INT          DEFAULT 60,             -- 持续 N 秒才触发
    silence_seconds   INT          DEFAULT 0,              -- 触发后静默期（秒）
    enabled           BOOLEAN      DEFAULT TRUE,
    created_at        TIMESTAMP    DEFAULT NOW(),
    updated_at        TIMESTAMP    DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_alert_rule_enabled ON alert_rule(enabled);
CREATE INDEX IF NOT EXISTS idx_alert_rule_service ON alert_rule(service);

-- =====================================================
-- 告警事件（T06/T07）
-- =====================================================
CREATE TABLE IF NOT EXISTS alert_event (
    id              BIGSERIAL PRIMARY KEY,
    rule_id         BIGINT NOT NULL,
    rule_name       VARCHAR(128),
    service         VARCHAR(128),
    metric          VARCHAR(64),
    operator        VARCHAR(8),
    threshold       DOUBLE PRECISION,
    current_value   DOUBLE PRECISION,
    status          VARCHAR(16) NOT NULL,     -- FIRING / RESOLVED / SILENCED / ACKNOWLEDGED
    detail          TEXT,
    fired_at        TIMESTAMP DEFAULT NOW(),
    resolved_at     TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_alert_event_status ON alert_event(status, fired_at);
CREATE INDEX IF NOT EXISTS idx_alert_event_rule ON alert_event(rule_id);
CREATE INDEX IF NOT EXISTS idx_alert_event_service ON alert_event(service, fired_at);
