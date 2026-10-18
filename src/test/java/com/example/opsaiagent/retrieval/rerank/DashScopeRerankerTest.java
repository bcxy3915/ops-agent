package com.example.opsaiagent.retrieval.rerank;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
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
 * 覆盖：重排成功按 index 排序 / 空文档 / 接口失败降级 / 响应解析失败返回原文
 */
@DisplayName("DashScope Reranker")
class DashScopeRerankerTest {

    private static final String API_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private MockRestServiceServer server;
    private DashScopeReranker reranker;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        reranker = new DashScopeReranker();
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
                        "{\"output\":{\"results\":[{\"index\":1},{\"index\":0}]}}",
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
    @DisplayName("rerank：接口失败降级为不重排截断")
    void rerank_apiFailureFallsBack() {
        server.expect(requestTo(API_URL)).andRespond(withServerError());

        List<Document> result = reranker.rerank("q", List.of(doc("a"), doc("b"), doc("c")), 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).isEqualTo("a");
    }

    @Test
    @DisplayName("rerank：响应非 JSON 时返回原文")
    void rerank_invalidResponseReturnsOriginals() {
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));

        List<Document> result = reranker.rerank("q", List.of(doc("a"), doc("b")), 2);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("rerank：成功路径按服务端 results 原样返回（客户端不额外截断）")
    void rerank_topNLimit() {
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(
                        "{\"output\":{\"results\":[{\"index\":0},{\"index\":1},{\"index\":2}]}}",
                        MediaType.APPLICATION_JSON));

        List<Document> result = reranker.rerank("q", List.of(doc("a"), doc("b"), doc("c")), 2);

        // 客户端不截断，原样返回服务端 results
        assertThat(result).hasSize(3);
    }
}
