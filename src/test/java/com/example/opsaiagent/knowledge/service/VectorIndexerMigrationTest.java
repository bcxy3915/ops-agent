package com.example.opsaiagent.knowledge.service;

import com.example.opsaiagent.knowledge.ingest.splitter.TextChunkStrategyResolver;
import com.example.opsaiagent.retrieval.KnowledgeVectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import java.util.List;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 知识索引生产入口的存储迁移接线测试。 */
class VectorIndexerMigrationTest {
    /**
     * 验证已迁移分支按十条分批写入，完全绕过 Spring AI 存储。
     * @return 无返回值
     */
    @Test
    void migratedWriteBypassesSpringAiStore() {
        VectorStore original = mock(VectorStore.class);
        KnowledgeVectorStore migrated = mock(KnowledgeVectorStore.class);
        VectorIndexer indexer = new VectorIndexer(original, mock(TextChunkStrategyResolver.class));
        indexer.setKnowledgeVectorStore(migrated);
        List<Document> docs = IntStream.range(0, 11).mapToObj(i -> new Document("片段" + i)).toList();
        assertThat(indexer.write(docs)).isEqualTo(11);
        var order = inOrder(migrated);
        order.verify(migrated).write(docs.subList(0, 10));
        order.verify(migrated).write(docs.subList(10, 11));
        verifyNoInteractions(original);
    }

    /**
     * 验证默认配置保留原有写入链路。
     * @return 无返回值
     */
    @Test
    void defaultWritePreservesOriginalStore() {
        VectorStore original = mock(VectorStore.class);
        VectorIndexer indexer = new VectorIndexer(original, mock(TextChunkStrategyResolver.class));
        var docs = List.of(new Document("片段"));
        assertThat(indexer.write(docs)).isEqualTo(1);
        verify(original).add(docs);
    }
}
