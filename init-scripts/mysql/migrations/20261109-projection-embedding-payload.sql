-- 已执行早期 MySQL 初始化脚本的环境使用；新环境 schema.sql 已包含最终结构。
-- 仅调整投递载荷，不删除业务表或 ES 数据；成功投递后的向量不长期占用业务库。
USE `opsdb`;
ALTER TABLE `knowledge_search_projection`
    MODIFY COLUMN `embedding` JSON NULL COMMENT '投递前临时向量；ES 确认后清空，不在本库执行向量检索';
UPDATE `knowledge_search_projection` SET `embedding` = NULL WHERE `sync_status` = 'SYNCED';
