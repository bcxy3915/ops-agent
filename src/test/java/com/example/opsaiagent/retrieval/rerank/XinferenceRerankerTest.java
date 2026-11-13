package com.example.opsaiagent.retrieval.rerank;

import com.example.opsaiagent.knowledge.model.Document;
import com.example.opsaiagent.resilience.ResilienceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 验证 Xinference 协议、排序、供应方选择及安全降级。 */
class XinferenceRerankerTest {
    private XinferenceReranker reranker;
    private MockRestServiceServer server;

    /**
     * 创建独立的可控 HTTP 客户端，不连接真实服务。
     * 无入参。
     * @return 无返回值
     */
    @BeforeEach
    void setup() {
        reranker = new XinferenceReranker();
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(reranker, "client", builder.build());
        ReflectionTestUtils.setField(reranker, "url", "http://localhost:9997/v1/rerank");
        ReflectionTestUtils.setField(reranker, "model", "ops-bge-reranker");
        ReflectionTestUtils.setField(reranker, "apiKey", "test-key");
    }

    /**
     * 平铺请求携带认证，按分数排序且不修改原始候选评分或权限元数据。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void sortsResultsAndPreservesOriginals() {
        Document a = new Document("a", "缓存", Map.of("tenantId", "tenant-a"), 0.5);
        Document b = new Document("b", "连接池", Map.of("source", "pool.md"), 0.3);
        server.expect(requestTo("http://localhost:9997/v1/rerank"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(content().json("""
                        {"model":"ops-bge-reranker","query":"连接池耗尽","documents":["缓存","连接池"],
                         "top_n":2,"return_documents":false,"normalize":true}
                        """))
                .andRespond(withSuccess("""
                        {"results":[{"index":0,"relevance_score":0.6},{"index":1,"relevance_score":0.9}]}
                        """, MediaType.APPLICATION_JSON));
        List<Document> result = reranker.rerank("连接池耗尽", List.of(a, b), 5);
        assertThat(result).extracting(Document::getId).containsExactly("b", "a");
        assertThat(result.getFirst().getScore()).isEqualTo(0.3);
        assertThat(result.getFirst().getMetadata()).containsEntry("source", "pool.md").containsEntry("rerankScore", 0.9);
        assertThat(result.get(1).getMetadata()).containsEntry("tenantId", "tenant-a");
        assertThat(b.getMetadata()).doesNotContainKey("rerankScore");
        server.verify();
    }

    /**
     * 服务失败仅发送一次请求并返回空知识上下文。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void unavailableReturnsEmptyWithoutRetry() {
        server.expect(requestTo("http://localhost:9997/v1/rerank")).andRespond(withServerError());
        Document a = new Document("a");
        assertThat(reranker.rerank("问题", List.of(a, new Document("b")), 1)).isEmpty();
        server.verify();
    }

    /**
     * 无效响应不能将未经精排校验的文档放进上下文。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void malformedResponsesReturnEmpty() {
        for (String response : List.of("not json", "{}", "{\"results\":[]}",
                "{\"results\":[{\"index\":9,\"relevance_score\":0.8}]}",
                "{\"results\":[{\"index\":0,\"relevance_score\":\"bad\"}]}",
                "{\"results\":[{\"index\":0,\"relevance_score\":7.2}]}",
                "{\"results\":[{\"index\":0,\"relevance_score\":-3.1}]}")) {
            server.reset();
            server.expect(requestTo("http://localhost:9997/v1/rerank"))
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
            Document original = new Document("a");
            assertThat(reranker.rerank("问题", List.of(original), 1)).isEmpty();
            server.verify();
        }
    }

    /**
     * 空候选与非正数量不发出网络请求。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void emptyInputSkipsCall() {
        assertThat(reranker.rerank("问题", null, 5)).isEmpty();
        assertThat(reranker.rerank("问题", List.of(new Document("a")), 0)).isEmpty();
        server.verify();
    }

    @Test
    void unrelatedCandidatesAreAllRejectedEvenWithHighVectorScores() {
        server.expect(requestTo("http://localhost:9997/v1/rerank"))
                .andRespond(withSuccess("""
                        {"results":[{"index":0,"relevance_score":0.03},{"index":1,"relevance_score":0.02}]}
                        """, MediaType.APPLICATION_JSON));
        List<Document> candidates = List.of(
                new Document("es", "ES 使用 9200 端口", Map.of(), 0.95),
                new Document("mysql", "MySQL 连接池排查", Map.of(), 0.92));
        assertThat(reranker.rerank("红烧肉怎么做", candidates, 5)).isEmpty();
        assertThat(candidates).allSatisfy(doc -> assertThat(doc.getMetadata()).doesNotContainKey("rerankScore"));
        server.verify();
    }

    @Test
    void filtersBeforeLimitingAndDoesNotFillToFive() {
        server.expect(requestTo("http://localhost:9997/v1/rerank"))
                .andRespond(withSuccess("""
                        {"results":[{"index":0,"relevance_score":0.1},{"index":1,"relevance_score":0.8},
                                    {"index":2,"relevance_score":0.95}]}
                        """, MediaType.APPLICATION_JSON));
        assertThat(reranker.rerank("es", List.of(new Document("无关"), new Document("ES 端口"),
                new Document("ES 部署")), 1)).extracting(Document::getText).containsExactly("ES 部署");
        server.verify();
    }

    @Test
    void calibratedScoresKeepShortEsQueryAndDropUncoveredQuestion() {
        // 本地部署文档节选的小样本分数，仅验证阈值行为，不声称替代完整相关性评测。
        for (double score : List.of(0.1718776, 0.2202192, 0.8355275, 0.0318873, 0.0004209)) {
            server.reset();
            server.expect(requestTo("http://localhost:9997/v1/rerank"))
                    .andRespond(withSuccess("{\"results\":[{\"index\":0,\"relevance_score\":" + score + "}]}",
                            MediaType.APPLICATION_JSON));
            List<Document> result = reranker.rerank("测试问题", List.of(new Document("ES 部署文档节选")), 5);
            assertThat(result).hasSize(score >= 0.1 ? 1 : 0);
            server.verify();
        }
    }

    @Test
    void busyRerankerReturnsNoUnvalidatedCandidates() throws Exception {
        var capacity = (Semaphore) ReflectionTestUtils.getField(reranker, "capacity");
        capacity.acquire();
        try {
            assertThat(reranker.rerank("es", List.of(new Document("ES 部署")), 5)).isEmpty();
            server.verify();
        } finally {
            capacity.release();
        }
    }

    /**
     * 未开启、默认百炼和 Xinference 配置均只注册一个实现。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void selectsExactlyOneProvider() {
        var runner = new ApplicationContextRunner()
                .withUserConfiguration(XinferenceReranker.class, DashScopeReranker.class, NoopReranker.class)
                .withBean(ResilienceProperties.class, ResilienceProperties::new);
        runner.run(context -> assertThat(context).hasSingleBean(RerankerClient.class).hasSingleBean(NoopReranker.class));
        runner.withPropertyValues("ops-agent.retrieval.rerank.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(RerankerClient.class).hasSingleBean(DashScopeReranker.class));
        runner.withPropertyValues("ops-agent.retrieval.rerank.enabled=true", "ops-agent.retrieval.rerank.provider=xinference")
                .run(context -> assertThat(context).hasSingleBean(RerankerClient.class).hasSingleBean(XinferenceReranker.class));
    }
}
