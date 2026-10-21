-- =====================================================
-- P0 变更：告警已读持久化 / 状态机四态 / 认领 / 升级（T19）
-- 幂等：IF NOT EXISTS / ADD COLUMN IF NOT EXISTS，可重复执行
-- =====================================================

-- -----------------------------------------------------
-- 1. 用户已读表（已读 ≠ 已处理；已读只影响铃铛计数）
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS user_alert_read (
    user_id         VARCHAR(64)  NOT NULL,          -- 登录用户名
    alert_event_id  BIGINT       NOT NULL,
    read_at         TIMESTAMP    DEFAULT NOW(),
    PRIMARY KEY (user_id, alert_event_id)
);

CREATE INDEX IF NOT EXISTS idx_user_alert_read_user ON user_alert_read(user_id);

-- -----------------------------------------------------
-- 2. alert_event：认领人 / 手动静默（到期时间+原因）/ 升级级别
-- -----------------------------------------------------
ALTER TABLE alert_event ADD COLUMN IF NOT EXISTS acknowledged_by VARCHAR(64);
ALTER TABLE alert_event ADD COLUMN IF NOT EXISTS silenced_until   TIMESTAMP;
ALTER TABLE alert_event ADD COLUMN IF NOT EXISTS silenced_reason  VARCHAR(256);
ALTER TABLE alert_event ADD COLUMN IF NOT EXISTS escalation_level INT DEFAULT 0;
ALTER TABLE alert_event ADD COLUMN IF NOT EXISTS escalated_at     TIMESTAMP;

-- -----------------------------------------------------
-- 3. alert_rule：升级策略（P0：5min 未确认升 1 级，最多 N 级）
-- -----------------------------------------------------
ALTER TABLE alert_rule ADD COLUMN IF NOT EXISTS escalation_enabled          BOOLEAN DEFAULT FALSE;
ALTER TABLE alert_rule ADD COLUMN IF NOT EXISTS escalation_interval_seconds INT     DEFAULT 300;
ALTER TABLE alert_rule ADD COLUMN IF NOT EXISTS escalation_max_level        INT     DEFAULT 2;

-- =====================================================
-- P0 增量（对齐《P0-前端交互规格-v1.md》，幂等可重复执行）
-- =====================================================

-- -----------------------------------------------------
-- 4. alert_event：处置时间线扩展字段（认领时间/恢复人/恢复备注/静默人/静默前状态）
-- -----------------------------------------------------
ALTER TABLE alert_event ADD COLUMN IF NOT EXISTS acknowledged_at  TIMESTAMP;
ALTER TABLE alert_event ADD COLUMN IF NOT EXISTS resolved_by      VARCHAR(64);
ALTER TABLE alert_event ADD COLUMN IF NOT EXISTS resolution_note  VARCHAR(500);
ALTER TABLE alert_event ADD COLUMN IF NOT EXISTS silenced_by      VARCHAR(64);
ALTER TABLE alert_event ADD COLUMN IF NOT EXISTS silenced_from    VARCHAR(16);
ALTER TABLE alert_event ADD COLUMN IF NOT EXISTS silenced_note    VARCHAR(500);
ALTER TABLE alert_event ADD COLUMN IF NOT EXISTS silenced_at      TIMESTAMP;

-- -----------------------------------------------------
-- 5. ops_user：显示名称（规格 5.1 displayName）
-- -----------------------------------------------------
ALTER TABLE ops_user ADD COLUMN IF NOT EXISTS display_name VARCHAR(50);

-- -----------------------------------------------------
-- 6. alert_rule_escalation_level：升级路径多级表（规格 4.1）
--    after_seconds 基于触发条件计时；trigger_on = UNACKNOWLEDGED / UNRESOLVED
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS alert_rule_escalation_level (
    id            BIGSERIAL PRIMARY KEY,
    rule_id       BIGINT       NOT NULL,
    level_no      INT          NOT NULL,
    after_seconds INT          NOT NULL,
    trigger_on    VARCHAR(16)  NOT NULL DEFAULT 'UNACKNOWLEDGED',
    to_role       VARCHAR(16),
    to_users      VARCHAR(512),
    channel_ids   VARCHAR(512),
    created_at    TIMESTAMP    DEFAULT NOW(),
    CONSTRAINT uk_rule_level UNIQUE (rule_id, level_no)
);

CREATE INDEX IF NOT EXISTS idx_escalation_level_rule ON alert_rule_escalation_level(rule_id);

-- -----------------------------------------------------
-- 7. alert_escalation：升级事件记录（规格 4.4，详情页时间线数据源）
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS alert_escalation (
    id             BIGSERIAL PRIMARY KEY,
    alert_event_id BIGINT      NOT NULL,
    level          INT         NOT NULL,
    trigger_on     VARCHAR(16),
    to_role        VARCHAR(16),
    to_users       VARCHAR(512),
    reason         VARCHAR(256),
    triggered_at   TIMESTAMP   DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_alert_escalation_event ON alert_escalation(alert_event_id);

