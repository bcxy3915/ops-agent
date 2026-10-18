package com.example.opsaiagent.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.knowledge.entity.KnowledgeCaseEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 故障案例沉淀 Mapper
 */
@Mapper
public interface KnowledgeCaseMapper extends BaseMapper<KnowledgeCaseEntity> {
}
