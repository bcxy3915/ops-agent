package com.example.opsaiagent.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.knowledge.entity.KnowledgeChunkEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识文档分块 Mapper
 */
@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunkEntity> {

    /**
     * 批量插入分块（单条多行 INSERT，主键回填）。
     * 大文档分块可达数千条，避免逐条 INSERT 造成的网络往返与事务日志放大。
     */
    @Insert("<script>"
            + "INSERT INTO knowledge_chunk (doc_id, tenant_id, dept_id, ordinal, content, section, created_at) VALUES "
            + "<foreach collection='list' item='item' separator=','>"
            + "(#{item.docId}, #{item.tenantId}, #{item.deptId}, #{item.ordinal}, #{item.content}, #{item.section}, NOW())"
            + "</foreach>"
            + "</script>")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertBatch(@Param("list") List<KnowledgeChunkEntity> list);
}
