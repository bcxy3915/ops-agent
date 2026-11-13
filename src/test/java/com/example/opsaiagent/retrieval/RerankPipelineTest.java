package com.example.opsaiagent.retrieval;

import com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jKnowledgeContentRetriever;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.opsaiagent.knowledge.model.Document;
import com.example.opsaiagent.knowledge.service.KnowledgeRetrievalService;
import com.example.opsaiagent.rbac.service.DataScope;
import com.example.opsaiagent.retrieval.diversity.RetrievalDiversifier;
import com.example.opsaiagent.retrieval.fusion.RrfFuser;
import com.example.opsaiagent.retrieval.rerank.XinferenceReranker;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.ContentMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 用模拟精排 HTTP 响应串联真实融合、过滤、模型内容适配和来源生成。 */
class RerankPipelineTest {
    @Test
    void allTwentyWeakCandidatesProduceNoContextOrSources() throws Exception {
        assertPipeline("红烧肉怎么做", 0.000421, 0.03, List.of());
    }

    @Test
    void relevantCandidatesNearEndSurviveAndRemainAlignedWithSources() throws Exception {
        assertPipeline("Elasticsearch 怎么部署", 0.9, 0.8, List.of("18", "19"));
    }

    @Test
    void shortEsQueryCanRetainOneSourceWithoutFillingFive() throws Exception {
        assertPipeline("es", 0.171878, 0.031887, List.of("18"));
    }

    /**
     * 双路各返回 20 个夹具，候选末尾放置待验证分数；断言最终来源与模型上下文完全同组。
     * @param query 检索问题
     * @param firstScore 第 19 个候选的模拟精排分数
     * @param secondScore 第 20 个候选的模拟精排分数
     * @param expectedIds 期望保留的分块标识
     * @return 无返回值
     */
    private void assertPipeline(String query, double firstScore, double secondScore, List<String> expectedIds) throws Exception {
        List<Document> candidates = IntStream.range(0, 20).mapToObj(index ->
                new Document(String.valueOf(index), "测试知识片段 " + index,
                        Map.of("source", "fixture-" + index + ".md"), 0.95)).toList();
        var vector = mock(KnowledgeVectorStore.class);
        var keyword = mock(KeywordRetriever.class);
        var scope = RetrievalScope.publicOnly();
        when(vector.search(query, 20, scope)).thenReturn(candidates);
        when(keyword.search(query, 20, scope)).thenReturn(candidates);
        RestClient.Builder builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var reranker = new XinferenceReranker();
        ReflectionTestUtils.setField(reranker, "client", builder.build());
        ReflectionTestUtils.setField(reranker, "url", "http://localhost:9997/v1/rerank");
        ReflectionTestUtils.setField(reranker, "model", "test-bge");
        List<Map<String, Object>> scores = IntStream.range(0, 20).mapToObj(index ->
                Map.<String, Object>of("index", index, "relevance_score",
                        index == 18 ? firstScore : index == 19 ? secondScore : 0.01)).toList();
        server.expect(requestTo("http://localhost:9997/v1/rerank"))
                .andExpect(jsonPath("$.documents.length()").value(20))
                .andExpect(jsonPath("$.top_n").value(5))
                .andExpect(jsonPath("$.normalize").value(true))
                .andRespond(withSuccess(new ObjectMapper().writeValueAsString(Map.of("results", scores)), MediaType.APPLICATION_JSON));
        var diversifier = new RetrievalDiversifier();
        ReflectionTestUtils.setField(diversifier, "enabled", true);
        ReflectionTestUtils.setField(diversifier, "maxPerDoc", 3);
        var hybrid = new HybridRetriever(vector, keyword, new RrfFuser(), reranker, mock(DataScope.class), diversifier);

        List<Document> finalDocuments = hybrid.retrieve(query, 5, scope);
        assertThat(finalDocuments).extracting(Document::getId).containsExactlyElementsOf(expectedIds);
        var embedding = mock(EmbeddingModel.class);
        var citations = new KnowledgeRetrievalService(hybrid, mock(KnowledgeDocumentMapper.class), embedding)
                .toHits(finalDocuments, query, 5);
        var contents = new LangChain4jKnowledgeContentRetriever(hybrid, 5).toContents(finalDocuments);
        assertThat(citations).extracting(hit -> hit.getSource()).containsExactlyElementsOf(
                finalDocuments.stream().map(doc -> doc.getMetadata().get("source").toString()).toList());
        assertThat(contents).extracting(content -> content.textSegment().text()).containsExactlyElementsOf(
                finalDocuments.stream().map(Document::getText).toList());
        for (int i = 0; i < citations.size(); i++) {
            assertThat(contents.get(i).metadata().get(ContentMetadata.SCORE)).isEqualTo(citations.get(i).getScore());
        }
        verifyNoInteractions(embedding);
        verify(vector).search(query, 20, scope);
        verify(keyword).search(query, 20, scope);
        server.verify();
    }
}
