package com.example.opsaiagent.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.knowledge.entity.KnowledgeConflictEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 上传质检冲突记录 Mapper（P1）
 */
@Mapper
public interface KnowledgeConflictMapper extends BaseMapper<KnowledgeConflictEntity> {
}
