package com.example.opsaiagent.retrieval.rerank;

import com.example.opsaiagent.knowledge.model.Document;
import com.example.opsaiagent.resilience.ResilienceProperties;
import com.example.opsaiagent.resilience.RestClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/** Xinference 精排适配：对已授权候选打分并过滤，异常、繁忙和熔断时返回空结果。 */
@Slf4j
@Component
@ConditionalOnExpression("${ops-agent.retrieval.rerank.enabled:false} && '${ops-agent.retrieval.rerank.provider:dashscope}' == 'xinference'")
public class XinferenceReranker implements RerankerClient {
    @Value("${ops-agent.retrieval.rerank.url:http://127.0.0.1:9997/v1/rerank}")
    private String url;
    @Value("${ops-agent.retrieval.rerank.model:ops-bge-reranker}")
    private String model;
    @Value("${ops-agent.retrieval.rerank.api-key:}")
    private String apiKey;
    @Value("${ops-agent.retrieval.rerank.connect-timeout-ms:1000}")
    private int connectTimeoutMs;
    @Value("${ops-agent.retrieval.rerank.read-timeout-ms:45000}")
    private int readTimeoutMs;
    @Value("${ops-agent.retrieval.rerank.min-score:0.15}")
    private double minScore = 0.15;
    private final ObjectMapper mapper = new ObjectMapper();
    private final CircuitBreaker breaker = CircuitBreaker.ofDefaults("xinference-rerank");
    private final Semaphore capacity = new Semaphore(1);
    private RestClient client;

    /**
     * 注入配置后创建客户端，使用独立超时且不重试，避免重复占用本地 CPU。
     * 无入参。
     * @return 无返回值
     */
    @PostConstruct
    public void initialize() {
        if (!StringUtils.hasText(url) || !StringUtils.hasText(model)
                || connectTimeoutMs <= 0 || readTimeoutMs <= 0
                || !Double.isFinite(minScore) || minScore < 0 || minScore > 1) {
            throw new IllegalArgumentException("Xinference 精排地址、模型标识和超时配置无效");
        }
        ResilienceProperties properties = new ResilienceProperties();
        properties.setConnectTimeoutMs(connectTimeoutMs);
        properties.setReadTimeoutMs(readTimeoutMs);
        client = RestClientFactory.builder(properties).build();
    }

    /**
     * 调用已部署的精排模型，仅传正文并通过下标映射原文档，不发送权限元数据。
     * @param query 检索问题
     * @param documents 已授权的候选文档
     * @param topN 返回数量上限
     * @return 通过相关性阈值的副本文档，最多 topN 条；不可用时为空
     */
    @Override
    public List<Document> rerank(String query, List<Document> documents, int topN) {
        if (documents == null || documents.isEmpty() || topN <= 0) return List.of();
        int limit = Math.min(topN, documents.size());
        if (!StringUtils.hasText(query)) return List.of();
        if (!capacity.tryAcquire()) {
            log.warn("Xinference 精排繁忙，本轮不提供未经筛选的知识上下文");
            return List.of();
        }
        try {
            return breaker.executeSupplier(() -> {
                var request = client.post().uri(url).contentType(MediaType.APPLICATION_JSON);
                if (StringUtils.hasText(apiKey)) request.headers(headers -> headers.setBearerAuth(apiKey.trim()));
                String response = request.body(Map.of(
                                "model", model, "query", query,
                                "documents", documents.stream().map(Document::getText).toList(),
                                "top_n", limit, "return_documents", false, "normalize", true))
                        .retrieve().body(String.class);
                return parse(response, documents, limit);
            });
        } catch (Exception failure) {
            // 不打印请求、响应或异常消息，防止正文或认证信息进入日志。
            log.warn("Xinference 精排失败，本轮不提供知识上下文，异常类型={}", failure.getClass().getSimpleName());
            return List.of();
        } finally {
            capacity.release();
        }
    }

    /**
     * 校验下标及分数后排序，保留原评分口径，单独记录精排分数。
     * @param response 精排接口响应
     * @param originals 原候选文档
     * @param limit 本次请求数量
     * @return 按精排分数降序排列的副本
     */
    private List<Document> parse(String response, List<Document> originals, int limit) {
        try {
            List<Document> ranked = RerankResultSelector.select(mapper.readTree(response).path("results"),
                    originals, limit, minScore, model);
            log.info("Xinference 精排完成，输入 {} 个，阈值 {}，有效输出 {} 个", originals.size(), minScore, ranked.size());
            return ranked;
        } catch (Exception failure) {
            throw new IllegalArgumentException("精排响应解析失败", failure);
        }
    }
}
