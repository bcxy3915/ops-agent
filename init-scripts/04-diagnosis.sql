-- =====================================================
-- LLM 智能诊断记录（T14/T15/T16/T17）
-- =====================================================
CREATE TABLE IF NOT EXISTS diagnosis_record (
    id             BIGSERIAL PRIMARY KEY,
    alert_event_id BIGINT NOT NULL,
    snapshot_id    BIGINT,
    rule_id        BIGINT,
    rule_name      VARCHAR(128),
    service        VARCHAR(128),
    metric         VARCHAR(64),
    status         VARCHAR(16) NOT NULL,      -- SUCCESS / FAILED
    root_cause     TEXT,                       -- 根因判断
    evidence       TEXT,                       -- 证据列表（JSON 数组）
    confidence     INT,                        -- 置信度 0-100
    suggestions    TEXT,                       -- 处置建议（JSON 数组）
    summary        TEXT,                       -- 诊断摘要
    question       VARCHAR(512),               -- 追问问题（主诊断为空）
    answer         TEXT,                       -- 追问回答（追问时填充）
    error_message  TEXT,
    created_at     TIMESTAMP DEFAULT NOW(),
    updated_at     TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_diagnosis_alert ON diagnosis_record(alert_event_id);
CREATE INDEX IF NOT EXISTS idx_diagnosis_service ON diagnosis_record(service, created_at);
CREATE INDEX IF NOT EXISTS idx_diagnosis_rule ON diagnosis_record(rule_id);
