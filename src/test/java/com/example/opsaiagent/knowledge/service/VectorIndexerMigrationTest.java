package com.example.opsaiagent.knowledge.service;

import com.example.opsaiagent.knowledge.ingest.splitter.TextChunkStrategyResolver;
import com.example.opsaiagent.retrieval.KnowledgeVectorStore;
import org.junit.jupiter.api.Test;
import com.example.opsaiagent.knowledge.model.Document;
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
        KnowledgeVectorStore migrated = mock(KnowledgeVectorStore.class);
        VectorIndexer indexer = new VectorIndexer(migrated, mock(TextChunkStrategyResolver.class));
        List<Document> docs = IntStream.range(0, 11).mapToObj(i -> new Document("片段" + i)).toList();
        assertThat(indexer.write(docs)).isEqualTo(11);
        var order = inOrder(migrated);
        order.verify(migrated).write(docs.subList(0, 10));
        order.verify(migrated).write(docs.subList(10, 11));
    }

    /**
     * 验证写入失败向上层传播，禁止伪造成功条数。
     * @return 无返回值
     */
    @Test
    void writeFailurePropagates() {
        KnowledgeVectorStore store = mock(KnowledgeVectorStore.class);
        VectorIndexer indexer = new VectorIndexer(store, mock(TextChunkStrategyResolver.class));
        var docs = List.of(new Document("片段"));
        doThrow(new IllegalStateException("存储失败")).when(store).write(docs);
        assertThatThrownBy(() -> indexer.write(docs)).hasMessage("存储失败");
    }
}
