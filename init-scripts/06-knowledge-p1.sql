-- =====================================================
-- 知识库管理增强（P1 二期 / P2 三期 新增）
-- =====================================================

-- knowledge_document 增加运营字段（幂等）
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS effective_at     TIMESTAMP;  -- 生效日期（在线编写/编辑时设置）
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS review_at        TIMESTAMP;  -- 复核到期时间（超期判定依据）
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS conflict_status  VARCHAR(16) DEFAULT 'NONE'; -- 冲突状态: NONE / KEEP_NEW / KEEP_OLD / MANUAL

-- 命中/引用流水表（对话引用、检索测试都会写入，用于命中统计/单篇反查/案例引用次数）
-- hit_source: ASK(对话引用) / TEST(检索测试) / RETRIEVE(其他检索)
CREATE TABLE IF NOT EXISTS knowledge_hit_log (
    id          BIGSERIAL PRIMARY KEY,
    doc_id      BIGINT       NOT NULL,                -- 命中的知识文档 ID
    query       TEXT,                                 -- 触发检索的问题
    hit_source  VARCHAR(16)  NOT NULL DEFAULT 'ASK',  -- 命中来源
    channel     VARCHAR(32),                          -- 渠道标识（用户名 / 测试会话）
    created_at  TIMESTAMP    DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_hit_doc ON knowledge_hit_log(doc_id, created_at);
CREATE INDEX IF NOT EXISTS idx_knowledge_hit_source ON knowledge_hit_log(hit_source, created_at);

-- 上传质检冲突记录表（标题/内容相似度检测后落库，供人工三态裁定）
-- conflict_type: TITLE(标题相似) / CONTENT(内容语义相似)
-- status: PENDING(待裁定) / RESOLVED(已裁定)
-- decision: KEEP_NEW(以新为准) / KEEP_OLD(保留原值) / MANUAL(标记人工裁定)
CREATE TABLE IF NOT EXISTS knowledge_conflict (
    id              BIGSERIAL PRIMARY KEY,
    doc_id          BIGINT      NOT NULL,             -- 引发冲突的文档（新上传/新编写）
    matched_doc_id  BIGINT,                           -- 冲突匹配到的已有文档
    conflict_type   VARCHAR(16) NOT NULL,             -- 冲突类型
    similarity      NUMERIC(5, 4),                    -- 相似度 0-1
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    decision        VARCHAR(16),                      -- 三态裁定结果
    resolved_by     VARCHAR(64),                      -- 裁定人
    created_at      TIMESTAMP   DEFAULT NOW(),
    resolved_at     TIMESTAMP                         -- 裁定时间
);

CREATE INDEX IF NOT EXISTS idx_knowledge_conflict_status ON knowledge_conflict(status);
CREATE INDEX IF NOT EXISTS idx_knowledge_conflict_doc ON knowledge_conflict(doc_id);

-- 文档版本历史表（编辑/审核发布时写入内容快照，支持版本列表与回滚）
CREATE TABLE IF NOT EXISTS knowledge_document_version (
    id               BIGSERIAL PRIMARY KEY,
    doc_id           BIGINT      NOT NULL,            -- 知识文档 ID
    version          INT         NOT NULL,            -- 版本号
    content_snapshot TEXT,                            -- 内容快照（raw_content）
    changed_by       VARCHAR(64),                     -- 变更人
    change_reason    VARCHAR(255),                    -- 变更原因
    created_at       TIMESTAMP   DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_version_doc ON knowledge_document_version(doc_id, version);
