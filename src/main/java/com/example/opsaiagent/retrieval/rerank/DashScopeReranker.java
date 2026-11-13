package com.example.opsaiagent.retrieval.rerank;

import com.example.opsaiagent.resilience.ResilienceProperties;
import com.example.opsaiagent.resilience.ResilienceSupport;
import com.example.opsaiagent.resilience.RestClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import com.example.opsaiagent.knowledge.model.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 阿里云百炼 Reranker 实现
 * 模型：gte-rerank-v2
 *
 * 网络调用经过 ResilienceSupport 保护：超时（RestClientFactory）+ 重试 + 熔断。
 * 使用精排分数过滤弱相关候选；调用失败时返回空结果。
 */
@Slf4j
@Component
@ConditionalOnExpression("'${ops-agent.retrieval.rerank.provider:dashscope}' == 'dashscope'")
@ConditionalOnProperty(
        name = "ops-agent.retrieval.rerank.enabled",
        havingValue = "true"
)
public class DashScopeReranker implements RerankerClient {

    private static final String API_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${ops-agent.ai.embedding.api-key:${spring.ai.openai.embedding.api-key:${DASHSCOPE_API_KEY:}}}")
    private String apiKey;

    @Value("${ops-agent.retrieval.rerank.model:gte-rerank-v2}")
    private String model;
    @Value("${ops-agent.retrieval.rerank.min-score:0.1}")
    private double minScore = 0.1;

    public DashScopeReranker(ResilienceProperties resilienceProperties) {
        ResilienceProperties props = resilienceProperties == null ? new ResilienceProperties() : resilienceProperties;
        ResilienceSupport.configure(props);
        this.restClient = RestClientFactory.builder(props).build();
    }

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
        if (documents == null || documents.isEmpty() || topN <= 0 || !StringUtils.hasText(query)) {
            return List.of();
        }

        try {
            List<String> texts = documents.stream().map(Document::getText).toList();

            Map<String, Object> input = Map.of(
                    "query", query,
                    "documents", texts
            );
            Map<String, Object> parameters = Map.of(
                    "top_n", Math.min(topN, documents.size()),
                    "return_documents", false
            );
            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", input,
                    "parameters", parameters
            );

            String response = ResilienceSupport.execute("rerank", () -> restClient.post()
                    .uri(API_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class));
            return parseResponse(response, documents, topN);
        } catch (Exception e) {
            log.warn("百炼精排失败，本轮不提供知识上下文，异常类型={}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * 解析重排序结果
     *
     * @param response 响应
     * @param originals 原始文档列表
     * @param topN 数量上限
     * @return 重排序后的文档列表
     */
    private List<Document> parseResponse(String response, List<Document> originals, int topN) {
        try {
            List<Document> reranked = RerankResultSelector.select(
                    objectMapper.readTree(response).path("output").path("results"), originals, topN, minScore, model);
            log.info("百炼精排完成，输入 {} 个，阈值 {}，有效输出 {} 个", originals.size(), minScore, reranked.size());
            return reranked;
        } catch (Exception e) {
            log.warn("百炼精排响应解析失败，本轮不提供知识上下文，异常类型={}", e.getClass().getSimpleName());
            return List.of();
        }
    }
}
