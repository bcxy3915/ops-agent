package com.example.opsaiagent.retrieval.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 阿里云百炼 Reranker 实现
 * 模型：gte-rerank-v2
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "ops-agent.retrieval.rerank.enabled",
        havingValue = "true"
)
public class DashScopeReranker implements RerankerClient {

    private static final String API_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${spring.ai.openai.embedding.api-key}")
    private String apiKey;

    @Value("${ops-agent.retrieval.rerank.model:gte-rerank-v2}")
    private String model;

    /**
     * 对文档列表进行重排序
     *
     * @param query 查询
     * @param documents 待重排序的文档列表
     * @param topN 重排序后的文档数量
     * @return 重排序后的文档列表
     */
    @Override
    public List<Document> rerank(String query, List<Document> documents, int topN) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        try {
            List<String> texts = documents.stream().map(Document::getText).toList();

            Map<String, Object> input = Map.of(
                    "query", query,
                    "documents", texts
            );
            Map<String, Object> parameters = Map.of(
                    "top_n", topN,
                    "return_documents", false
            );
            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", input,
                    "parameters", parameters
            );

            String response = restClient.post()
                    .uri(API_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            log.debug("百炼 Reranker 响应: {}", response);

            return parseResponse(response, documents);
        } catch (Exception e) {
            log.error("Reranker 调用失败，降级为不重排", e);
            return documents.stream().limit(topN).toList();
        }
    }

    /**
     * 解析重排序结果
     *
     * @param response 响应
     * @param originals 原始文档列表
     * @return 重排序后的文档列表
     */
    private List<Document> parseResponse(String response, List<Document> originals) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("output").path("results");

            List<Document> reranked = new ArrayList<>();
            for (JsonNode item : results) {
                int index = item.path("index").asInt();
                if (index >= 0 && index < originals.size()) {
                    reranked.add(originals.get(index));
                }
            }
            log.info("Reranker 完成，输入 {} 个，输出 {} 个", originals.size(), reranked.size());
            return reranked;
        } catch (Exception e) {
            log.error("Reranker 响应解析失败: {}", response, e);
            return originals;
        }
    }
}