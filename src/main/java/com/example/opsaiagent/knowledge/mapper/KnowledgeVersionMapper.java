package com.example.opsaiagent.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentVersionEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识文档版本历史 Mapper（P2）
 */
@Mapper
public interface KnowledgeVersionMapper extends BaseMapper<KnowledgeDocumentVersionEntity> {
}
