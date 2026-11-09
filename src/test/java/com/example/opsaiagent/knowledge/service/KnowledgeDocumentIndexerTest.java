package com.example.opsaiagent.knowledge.service;

import com.example.opsaiagent.knowledge.entity.KnowledgeChunkEntity;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.ingest.splitter.SplitProperties;
import com.example.opsaiagent.knowledge.ingest.splitter.SplitterTestFixture;
import com.example.opsaiagent.knowledge.mapper.KnowledgeChunkMapper;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.opsaiagent.knowledge.model.Document;
import com.example.opsaiagent.retrieval.KnowledgeVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 共享索引器的内置与上传分支回归验证。 */
@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentIndexerTest {
    @Mock private KnowledgeDocumentMapper documents;
    @Mock private KnowledgeChunkMapper chunks;
    @Mock private KnowledgeVectorStore vectors;
    @Mock private JdbcTemplate jdbc;
    private KnowledgeDocumentIndexer indexer;

    /**
     * 准备真实切分器与模拟持久层；无入参。
     * @return 无返回值
     */
    @BeforeEach
    void setUp() {
        indexer = new KnowledgeDocumentIndexer(documents, chunks, SplitterTestFixture.vectorIndexer(vectors), vectors, new SplitProperties());
        when(chunks.insertBatch(any())).thenAnswer(invocation -> {
            List<KnowledgeChunkEntity> batch = invocation.getArgument(0);
            for (int i = 0; i < batch.size(); i++) batch.get(i).setId(100L + i);
            return batch.size();
        });
    }

    /**
     * 验证上传分支仍添加检索上下文头，同时展示内容保持原样；无入参。
     * @return 无返回值
     */
    @Test
    void uploadedDocumentKeepsEmbeddingHeaderAndPrivateScope() {
        KnowledgeDocumentEntity entity = document("PRIVATE", "t1", "PERSONAL");
        indexer.reindex(entity, "upload:1");
        ArgumentCaptor<List<Document>> captured = ArgumentCaptor.forClass(List.class);
        verify(vectors).write(captured.capture());
        assertThat(captured.getValue()).allSatisfy(vector -> {
            assertThat(vector.getText()).startsWith("【手册｜");
            assertThat(vector.getMetadata()).containsEntry("tenantId", "t1")
                    .containsEntry("visibility", "PERSONAL").containsEntry("source", "upload:1");
            assertThat(vector.getMetadata()).doesNotContainKey("contentHash");
        });
        ArgumentCaptor<List<KnowledgeChunkEntity>> content = ArgumentCaptor.forClass(List.class);
        verify(chunks).insertBatch(content.capture());
        assertThat(content.getValue()).allSatisfy(chunk -> assertThat(entity.getRawContent()).contains(chunk.getContent()));
    }

    /**
     * 验证内置分支使用公共范围、文件来源及指纹，不给正文注入额外内容；无入参。
     * @return 无返回值
     */
    @Test
    void builtinDocumentKeepsRawVectorTextAndPublicScope() {
        KnowledgeDocumentEntity entity = document("BUILTIN", "0", "PUBLIC");
        indexer.reindexBuiltin(entity, "hash");
        ArgumentCaptor<List<Document>> captured = ArgumentCaptor.forClass(List.class);
        verify(vectors).write(captured.capture());
        assertThat(captured.getValue()).allSatisfy(vector -> {
            assertThat(entity.getRawContent()).contains(vector.getText());
            assertThat(vector.getMetadata()).containsEntry("tenantId", "0")
                    .containsEntry("visibility", "PUBLIC").containsEntry("source", "manual.md")
                    .containsEntry("contentHash", "hash");
        });
    }

    /**
     * 创建待索引文档。
     * @param layer 文档层级
     * @param tenant 租户标识
     * @param visibility 可见范围
     * @return 包含 Markdown 源码的实体
     */
    private KnowledgeDocumentEntity document(String layer, String tenant, String visibility) {
        KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
        entity.setId(1L);
        entity.setTitle("手册");
        entity.setDocType("MARKDOWN");
        entity.setSource("manual.md");
        entity.setLayer(layer);
        entity.setTenantId(tenant);
        entity.setVisibility(visibility);
        entity.setRawContent("# 故障检查\n\n" + "先检查指标，再检查日志。".repeat(20));
        return entity;
    }
}
