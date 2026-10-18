package com.example.opsaiagent.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.knowledge.entity.KnowledgeHitLogEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识命中流水 Mapper（P1）
 */
@Mapper
public interface KnowledgeHitLogMapper extends BaseMapper<KnowledgeHitLogEntity> {
}
