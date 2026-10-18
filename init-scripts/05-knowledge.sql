-- =====================================================
-- 知识库（T18/T19/T20 新增）
-- =====================================================

-- 知识文档元数据表
-- layer: BUILTIN(内置官方手册) / PRIVATE(私有上传) / CASE(沉淀案例)
-- trust_status: VERIFIED(已验证) / PENDING(待审核) / AI_DRAFT(AI初稿待校对) / EXPIRED(已失效)
-- doc_type: MARKDOWN / PDF / TXT / DOCX
CREATE TABLE IF NOT EXISTS knowledge_document (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(255) NOT NULL,
    doc_type        VARCHAR(16)  NOT NULL DEFAULT 'MARKDOWN',
    layer           VARCHAR(16)  NOT NULL DEFAULT 'PRIVATE',
    trust_status    VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    version         INT          NOT NULL DEFAULT 1,
    owner           VARCHAR(64),
    service_ids     TEXT,                        -- 适用服务列表（JSON 数组）
    tags            TEXT,                        -- 标签（JSON 数组）
    hit_count       BIGINT       DEFAULT 0,      -- 被引用命中次数
    source          VARCHAR(255),                -- 来源标识（文件名 / 上传名）
    file_path       VARCHAR(512),                -- 文件存储路径（内置文档用 classpath 标识）
    raw_content     TEXT,                        -- 原始全文（供预览 / 分块）
    chunk_count     INT          DEFAULT 0,      -- 分块数量
    submitted_at    TIMESTAMP,                   -- 提交审核时间
    last_review_at  TIMESTAMP,                   -- 最近审核时间
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW()
    );

CREATE INDEX IF NOT EXISTS idx_knowledge_doc_layer ON knowledge_document(layer);
CREATE INDEX IF NOT EXISTS idx_knowledge_doc_status ON knowledge_document(trust_status);
CREATE INDEX IF NOT EXISTS idx_knowledge_doc_source ON knowledge_document(source);
CREATE INDEX IF NOT EXISTS idx_knowledge_doc_title ON knowledge_document(title);

-- 知识文档分块表
CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id              BIGSERIAL PRIMARY KEY,
    doc_id          BIGINT NOT NULL,
    ordinal         INT NOT NULL,                -- 块内序号
    content         TEXT NOT NULL,               -- 分块文本
    section         VARCHAR(255),                -- 所属章节/小节
    vector_id       VARCHAR(64),                 -- 关联 vector_store 的向量 ID（如可用）
    created_at      TIMESTAMP DEFAULT NOW()
    );

CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_doc ON knowledge_chunk(doc_id, ordinal);

-- 故障案例沉淀表
-- status: PENDING(待确认) / CONFIRMED(已沉淀) / DISCARDED(已丢弃)
-- masked_status: NONE / PARTIAL / FULL  脱敏状态
CREATE TABLE IF NOT EXISTS knowledge_case (
    id                  BIGSERIAL PRIMARY KEY,
    source_alert_id     BIGINT,                  -- 来源告警事件
    diagnosis_record_id BIGINT,                  -- 来源诊断记录
    service             VARCHAR(128),            -- 涉及服务
    title               VARCHAR(255) NOT NULL,
    symptom             TEXT,                    -- 故障现象
    root_cause          TEXT,                    -- AI 判断根因
    real_root_cause     TEXT,                    -- 人工确认/修正后的真实根因
    evidence            TEXT,                    -- 证据列表（JSON 数组）
    effective_action    TEXT,                    -- 有效处置动作
    confidence          INT,                     -- 置信度 0-100
    masked_status       VARCHAR(16) DEFAULT 'NONE',
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    duration_min        INT,                     -- 故障持续时长（分钟）
    closed_loop_at      TIMESTAMP,               -- 闭环时间
    confirmed_by        VARCHAR(64),             -- 确认人
    cited_count         INT DEFAULT 0,           -- 被引用次数
    created_at          TIMESTAMP DEFAULT NOW(),
    updated_at          TIMESTAMP DEFAULT NOW()
    );

CREATE INDEX IF NOT EXISTS idx_knowledge_case_status ON knowledge_case(status);
CREATE INDEX IF NOT EXISTS idx_knowledge_case_service ON knowledge_case(service, created_at);
CREATE INDEX IF NOT EXISTS idx_knowledge_case_diag ON knowledge_case(diagnosis_record_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_case_alert ON knowledge_case(source_alert_id);
