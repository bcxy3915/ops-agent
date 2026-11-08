-- 已有环境增量升级：仅新增问答租约表，不修改历史消息或重建向量。
-- 部署新版本前手动执行；应用不自动执行该脚本。
CREATE TABLE IF NOT EXISTS ops_chat_turn (
    tenant_id VARCHAR(32) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, session_id)
);
COMMENT ON TABLE ops_chat_turn IS '问答轮次租约；跨实例互斥、请求令牌与会话代次校验';
COMMENT ON COLUMN ops_chat_turn.request_id IS '每轮唯一令牌，防止失效请求写回或释放新租约';
COMMENT ON COLUMN ops_chat_turn.conversation_id IS '会话实体 ID，删除重建后旧请求自动失效';
COMMENT ON COLUMN ops_chat_turn.expires_at IS '使用数据库时钟计算的租约截止时间';
