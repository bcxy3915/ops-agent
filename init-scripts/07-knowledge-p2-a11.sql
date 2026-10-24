-- =====================================================
-- 知识包版本元数据（P2-A11 新增）
-- =====================================================

-- knowledge_document 增加知识包版本元数据（幂等）
-- 内置文档判定复用现有 layer 字段（BUILTIN），不新增 built_in 列（产品决策 #14）
-- package_version: 内置文档所属知识包版本（如 1.0.0），自定义文档为空
-- released_at: 内置文档随版本发布时间（自定义文档为空）
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS package_version  VARCHAR(32);                -- 知识包版本
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS released_at      TIMESTAMP;                  -- 随版本发布时间
