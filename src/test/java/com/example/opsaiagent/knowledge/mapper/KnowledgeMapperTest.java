package com.example.opsaiagent.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.knowledge.entity.KnowledgeCaseEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeChunkEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库 Mapper 接口结构测试
 * 验证三个 Mapper 均继承 BaseMapper 且泛型实体类型正确，保证 MyBatis-Plus CRUD 可用。
 */
@DisplayName("知识库 Mapper 结构")
class KnowledgeMapperTest {

    @Test
    @DisplayName("KnowledgeDocumentMapper 继承 BaseMapper<KnowledgeDocumentEntity>")
    void documentMapper() {
        assertBaseMapper(KnowledgeDocumentMapper.class, KnowledgeDocumentEntity.class);
    }

    @Test
    @DisplayName("KnowledgeChunkMapper 继承 BaseMapper<KnowledgeChunkEntity>")
    void chunkMapper() {
        assertBaseMapper(KnowledgeChunkMapper.class, KnowledgeChunkEntity.class);
    }

    @Test
    @DisplayName("KnowledgeCaseMapper 继承 BaseMapper<KnowledgeCaseEntity>")
    void caseMapper() {
        assertBaseMapper(KnowledgeCaseMapper.class, KnowledgeCaseEntity.class);
    }

    private void assertBaseMapper(Class<?> mapperClass, Class<?> expectedEntity) {
        assertThat(BaseMapper.class.isAssignableFrom(mapperClass)).isTrue();

        Type[] genericInterfaces = mapperClass.getGenericInterfaces();
        assertThat(genericInterfaces).hasSize(1);
        Type type = genericInterfaces[0];
        assertThat(type).isInstanceOf(ParameterizedType.class);
        ParameterizedType pt = (ParameterizedType) type;
        assertThat(pt.getRawType()).isEqualTo(BaseMapper.class);
        assertThat(pt.getActualTypeArguments()).hasSize(1);
        assertThat(pt.getActualTypeArguments()[0]).isEqualTo(expectedEntity);
    }
}
