-- 回填旧库迁移后缺失的分块向量 ID，仅关联同文档、未删除且已同步的投影。
-- 可重复执行；不覆盖已有向量 ID，不修改正文或向量内容。
UPDATE knowledge_chunk c
JOIN knowledge_search_projection p
  ON p.doc_id = c.doc_id
 AND p.deleted = FALSE
 AND p.sync_status = 'SYNCED'
 AND JSON_UNQUOTE(JSON_EXTRACT(p.metadata, '$.chunkId')) = CAST(c.id AS CHAR)
SET c.vector_id = p.vector_id
WHERE c.vector_id IS NULL;
