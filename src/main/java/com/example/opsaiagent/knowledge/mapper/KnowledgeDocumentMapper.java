package com.example.opsaiagent.knowledge.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识文档 Mapper
 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentEntity> {
    /**
     * 锁定文档，与编辑删除串行化，防止索引旧内容覆盖新版本。
     * @param id 文档 ID
     * @return 文档，可为空
     */
    @Select("SELECT * FROM knowledge_document WHERE id=#{id} FOR UPDATE")
    KnowledgeDocumentEntity lockById(@Param("id") Long id);

}
