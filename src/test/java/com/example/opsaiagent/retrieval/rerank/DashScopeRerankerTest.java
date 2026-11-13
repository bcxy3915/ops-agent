package com.example.opsaiagent.retrieval.rerank;

import com.example.opsaiagent.resilience.ResilienceProperties;
import com.example.opsaiagent.resilience.ResilienceSupport;
import com.example.opsaiagent.resilience.RestClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.example.opsaiagent.knowledge.model.Document;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * DashScopeReranker 单元测试
 * 覆盖：精排评分和过滤 / 空文档 / 接口失败返回空知识 / 非法响应 / 数量上限
 */
@DisplayName("DashScope Reranker")
class DashScopeRerankerTest {

    private static final String API_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private MockRestServiceServer server;
    private DashScopeReranker reranker;

    @BeforeEach
    void setUp() {
        ResilienceSupport.reset();
        ResilienceProperties props = new ResilienceProperties();
        props.setRetryWaitMs(1);
        RestClient.Builder builder = RestClientFactory.builder(props);
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        reranker = new DashScopeReranker(props);
        ReflectionTestUtils.setField(reranker, "restClient", restClient);
        ReflectionTestUtils.setField(reranker, "apiKey", "sk-test");
        ReflectionTestUtils.setField(reranker, "model", "gte-rerank-v2");
    }

    private Document doc(String text) {
        return new Document(text, Map.of("source", "test.md"));
    }

    @Test
    @DisplayName("rerank：按返回 index 重排")
    void rerank_success() {
        server.expect(requestTo(API_URL))
                .andExpect(header("Authorization", "Bearer sk-test"))
                .andRespond(withSuccess(
                        "{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.95},{\"index\":0,\"relevance_score\":0.6}]}}",
                        MediaType.APPLICATION_JSON));

        List<Document> result = reranker.rerank("q", List.of(doc("a"), doc("b")), 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).isEqualTo("b");
        assertThat(result.get(1).getText()).isEqualTo("a");
    }

    @Test
    @DisplayName("rerank：空文档直接返回空")
    void rerank_emptyDocuments() {
        assertThat(reranker.rerank("q", List.of(), 2)).isEmpty();
    }

    @Test
    @DisplayName("rerank：接口 500 时自动重试后返回空知识")
    void rerank_apiFailureFallsBack() {
        // 默认重试 2 次：两次都失败后降级
        server.expect(requestTo(API_URL)).andRespond(withServerError());
        server.expect(requestTo(API_URL)).andRespond(withServerError());

        List<Document> result = reranker.rerank("q", List.of(doc("a"), doc("b"), doc("c")), 2);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("rerank：响应非 JSON 时返回空知识")
    void rerank_invalidResponseReturnsOriginals() {
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));

        List<Document> result = reranker.rerank("q", List.of(doc("a"), doc("b")), 2);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("rerank：服务端超额返回时客户端仍遵守 topN")
    void rerank_topNLimit() {
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(
                        "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.8},{\"index\":1,\"relevance_score\":0.9},{\"index\":2,\"relevance_score\":0.7}]}}",
                        MediaType.APPLICATION_JSON));

        List<Document> result = reranker.rerank("q", List.of(doc("a"), doc("b"), doc("c")), 2);

        assertThat(result).extracting(Document::getText).containsExactly("b", "a");
    }

    @Test
    void rerank_unrelatedCandidatesReturnEmpty() {
        server.expect(requestTo(API_URL)).andRespond(withSuccess("""
                {"output":{"results":[{"index":0,"relevance_score":0.05},{"index":1,"relevance_score":0.03}]}}
                """, MediaType.APPLICATION_JSON));
        assertThat(reranker.rerank("红烧肉怎么做", List.of(doc("ES 部署"), doc("MySQL 连接池")), 5)).isEmpty();
        server.verify();
    }
}
