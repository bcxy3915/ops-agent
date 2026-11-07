package com.example.opsaiagent.ai.adapter.springai;

import com.example.opsaiagent.knowledge.config.LangChain4jEmbeddingConfig;
import com.example.opsaiagent.retrieval.RetrievalScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用本地 HTTP 夹具验证向量协议，JDBC 使用 Mock，不访问真实模型和数据库。 */
class LangChain4jEmbeddingProtocolTest {

    /**
     * 验证批量请求、文档入库和检索查询均使用 LangChain4j 模型。
     * @return 无返回值
     * @throws Exception 本地协议夹具异常
     */
    @Test
    void batchesAndPgvectorOperationsUseLangChain4j() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            try {
                JsonNode request = mapper.readTree(exchange.getRequestBody());
                requests.add(request);
                List<Map<String, Object>> vectors = new ArrayList<>();
                int index = 0;
                for (JsonNode input : request.path("input")) {
                    String text = input.asText();
                    int value = text.startsWith("条目") ? Integer.parseInt(text.substring(2)) : 99;
                    vectors.add(Map.of("object", "embedding", "index", index++,
                            "embedding", List.of(value, 1, 0)));
                }
                byte[] body = mapper.writeValueAsBytes(Map.of("object", "list", "data", vectors,
                        "model", "test-embedding", "usage", Map.of("prompt_tokens", 5, "total_tokens", 5)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            var model = new LangChain4jEmbeddingConfig().langChain4jEmbeddingModel(
                    baseUrl, "test-key", "test-embedding", 3, 3, "5s", 0);
            var bridge = new LangChain4jEmbeddingBridge(model, "test-embedding", 3, MetadataMode.EMBED);
            List<String> texts = IntStream.range(0, 11).mapToObj(i -> "条目" + i).toList();
            List<float[]> batch = bridge.embed(texts);
            assertThat(requests).hasSize(2);
            assertThat(requests.get(0).path("input")).hasSize(10);
            assertThat(requests.get(1).path("input")).hasSize(1);
            assertThat(batch).hasSize(11);
            for (int i = 0; i < batch.size(); i++) {
                assertThat(batch.get(i)).containsExactly(i, 1, 0);
            }

            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
            PgVectorStore store = PgVectorStore.builder(jdbc, bridge)
                    .dimensions(3).initializeSchema(false).build();
            store.add(List.of(new Document("运维处理步骤", Map.of("source", "fixture.md"))));
            assertThat(store.similaritySearch(SearchRequest.builder().query("检查服务")
                    .topK(5).similarityThreshold(0.5)
                    .filterExpression(RetrievalScope.publicOnly().filterExpression()).build())).isEmpty();
            assertThat(requests).hasSize(4);
            assertThat(requests.get(2).path("input").toString()).contains("运维处理步骤");
            assertThat(requests.get(3).path("input").get(0).asText()).isEqualTo("检查服务");
            for (JsonNode request : requests) {
                assertThat(request.path("model").asText()).isEqualTo("test-embedding");
                assertThat(request.path("dimensions").asInt()).isEqualTo(3);
            }
            verify(jdbc).query(contains("reviewStatus"), any(RowMapper.class), any(Object[].class));
        } finally {
            server.stop(0);
        }
    }
}
