package com.example.opsaiagent.ai.adapter.langchain4j;

import com.example.opsaiagent.retrieval.HybridRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.example.opsaiagent.knowledge.model.Document;

import java.util.List;
import java.util.Map;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** LangChain4j RAG 内容检索器测试。 */
@ExtendWith(MockitoExtension.class)
class LangChain4jKnowledgeContentRetrieverTest {

    @Mock
    private HybridRetriever hybridRetriever;

    @Test
    @DisplayName("空查询不访问混合检索并返回空结果")
    void blankQueryReturnsEmpty() {
        LangChain4jKnowledgeContentRetriever retriever =
                new LangChain4jKnowledgeContentRetriever(hybridRetriever, 5);

        assertThat(retriever.retrieveDocuments(" ")).isEmpty();
    }

    @Test
    @DisplayName("检索结果保留文本、文档 metadata 和 score")
    void convertsDocumentMetadataAndScore() {
        Document document = new Document("故障处理步骤", Map.of("source", "runbook.md", "score", 0.87));
        LangChain4jKnowledgeContentRetriever retriever =
                new LangChain4jKnowledgeContentRetriever(hybridRetriever, 5);

        List<Content> contents = retriever.toContents(Arrays.asList(document,
                new Document(" ", Map.of()), null));

        assertThat(contents).hasSize(1);
        Content content = contents.get(0);
        assertThat(content.textSegment().text()).isEqualTo("故障处理步骤");
        assertThat(content.textSegment().metadata().getString("source")).isEqualTo("runbook.md");
        assertThat(content.metadata()).containsEntry(ContentMetadata.SCORE, 0.87);
    }

    /**
     * 验证向量评分可用，并优先保留融合或重排评分。
     * @return 无返回值
     */
    @Test
    void preservesVectorScoreAndPrefersPipelineScore() {
        LangChain4jKnowledgeContentRetriever retriever =
                new LangChain4jKnowledgeContentRetriever(hybridRetriever, 5);
        List<Content> contents = retriever.toContents(List.of(
                new Document("vector", "向量结果", Map.of(), 0.8),
                new Document("reranked", "重排结果", Map.of("score", 0.95), 0.8)));

        assertThat(contents.get(0).metadata()).containsEntry(ContentMetadata.SCORE, 0.8);
        assertThat(contents.get(1).metadata()).containsEntry(ContentMetadata.SCORE, 0.95);
    }

    @Test
    @DisplayName("retrieve 委托混合检索并按 topK 转换结果")
    void retrieveDelegatesToHybridRetriever() {
        Document document = new Document("值班手册", Map.of("source", "oncall.md"));
        when(hybridRetriever.retrieve("值班", 3)).thenReturn(List.of(document));
        LangChain4jKnowledgeContentRetriever retriever =
                new LangChain4jKnowledgeContentRetriever(hybridRetriever, 3);

        List<Content> contents = retriever.retrieve(Query.from("值班"));

        verify(hybridRetriever).retrieve(eq("值班"), eq(3));
        assertThat(contents).singleElement()
                .extracting(content -> content.textSegment().text())
                .isEqualTo("值班手册");
    }

    @Test
    @DisplayName("空文档列表返回空结果")
    void emptyDocumentsReturnEmpty() {
        LangChain4jKnowledgeContentRetriever retriever =
                new LangChain4jKnowledgeContentRetriever(hybridRetriever, 5);

        assertThat(retriever.toContents(List.of())).isEmpty();
        assertThat(retriever.toContents(null)).isEmpty();
    }
}
