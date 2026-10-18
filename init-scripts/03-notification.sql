-- =====================================================
-- 通知渠道（T09/T10/T11）
-- =====================================================
CREATE TABLE IF NOT EXISTS notification_channel (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(128) NOT NULL UNIQUE,
    type        VARCHAR(32)  NOT NULL,      -- FEISHU_WEBHOOK / EMAIL / GENERIC_WEBHOOK
    enabled     BOOLEAN      DEFAULT TRUE,
    config      TEXT,                        -- JSON: webhookUrl / recipients / subjectPrefix / secret
    description VARCHAR(512),
    created_at  TIMESTAMP    DEFAULT NOW(),
    updated_at  TIMESTAMP    DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_notification_channel_type ON notification_channel(type);
CREATE INDEX IF NOT EXISTS idx_notification_channel_enabled ON notification_channel(enabled);

-- =====================================================
-- 通知发送记录（T10）
-- =====================================================
CREATE TABLE IF NOT EXISTS notification_record (
    id             BIGSERIAL PRIMARY KEY,
    alert_event_id BIGINT,
    rule_id        BIGINT,
    rule_name      VARCHAR(128),
    service        VARCHAR(128),
    metric         VARCHAR(64),
    channel_id     BIGINT,
    channel_name   VARCHAR(128),
    channel_type   VARCHAR(32),
    target         VARCHAR(512),
    title          VARCHAR(255),
    content        TEXT,
    status         VARCHAR(16) NOT NULL,     -- PENDING / SUCCESS / FAILED
    error_message  TEXT,
    retry_count    INT DEFAULT 0,
    max_retry      INT DEFAULT 2,
    next_retry_at  TIMESTAMP,
    sent_at        TIMESTAMP,
    created_at     TIMESTAMP DEFAULT NOW(),
    updated_at     TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_notification_record_status ON notification_record(status, next_retry_at);
CREATE INDEX IF NOT EXISTS idx_notification_record_channel ON notification_record(channel_id);
CREATE INDEX IF NOT EXISTS idx_notification_record_alert ON notification_record(alert_event_id);
CREATE INDEX IF NOT EXISTS idx_notification_record_service ON notification_record(service, created_at);

-- =====================================================
-- 故障快照（T12/T13）
-- =====================================================
CREATE TABLE IF NOT EXISTS failure_snapshot (
    id             BIGSERIAL PRIMARY KEY,
    alert_event_id BIGINT NOT NULL,
    rule_id        BIGINT,
    rule_name      VARCHAR(128),
    service        VARCHAR(128),
    metric         VARCHAR(64),
    status         VARCHAR(16),
    snapshot_json  TEXT NOT NULL,
    created_at     TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_failure_snapshot_alert ON failure_snapshot(alert_event_id);
CREATE INDEX IF NOT EXISTS idx_failure_snapshot_service ON failure_snapshot(service, created_at);
