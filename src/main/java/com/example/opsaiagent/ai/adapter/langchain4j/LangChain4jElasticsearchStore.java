package com.example.opsaiagent.ai.adapter.langchain4j;

import com.example.opsaiagent.knowledge.model.Document;
import com.example.opsaiagent.knowledge.model.EmbeddingContentFormatter;
import com.example.opsaiagent.knowledge.model.EmbeddingMetadataMode;
import com.example.opsaiagent.retrieval.KnowledgeVectorStore;
import com.example.opsaiagent.retrieval.RetrievalScope;
import com.example.opsaiagent.retrieval.elasticsearch.ElasticsearchGateway;
import com.example.opsaiagent.retrieval.elasticsearch.SearchProjectionService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** LangChain4j 生成向量，ES 执行检索，MySQL 持久化投递与权限复核。 */
public class LangChain4jElasticsearchStore implements KnowledgeVectorStore {
    private final EmbeddingModel model;
    private final EmbeddingMetadataMode metadataMode;
    private final int dimensions;
    private final ElasticsearchGateway es;
    private final SearchProjectionService projection;

    /**
     * 创建向量存储适配器。
     * @param model 向量模型
     * @param metadataMode 嵌入文本格式
     * @param dimensions 模型维度
     * @param es ES 请求边界
     * @param projection 事务投影
     * @return 无返回值
     */
    public LangChain4jElasticsearchStore(EmbeddingModel model, EmbeddingMetadataMode metadataMode,
                                        int dimensions, ElasticsearchGateway es, SearchProjectionService projection) {
        if (dimensions <= 0) throw new IllegalArgumentException("向量维度必须大于零");
        this.model = model;
        this.metadataMode = metadataMode;
        this.dimensions = dimensions;
        this.es = es;
        this.projection = projection;
    }

    /**
     * 分批生成向量并持久化到事务投影，ES 写入由后台补偿同步。
     * @param documents 分块列表
     * @return 无返回值
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void write(List<Document> documents) {
        for (int start = 0; start < documents.size(); start += 10) {
            var batch = documents.subList(start, Math.min(start + 10, documents.size()));
            var vectors = model.embedAll(batch.stream().map(doc -> TextSegment.from(
                    EmbeddingContentFormatter.format(doc, metadataMode))).toList()).content();
            if (vectors == null || vectors.size() != batch.size()) throw new IllegalStateException("模型返回向量数量错误");
            for (int i = 0; i < batch.size(); i++) {
                validate(vectors.get(i).vector());
                projection.stage(batch.get(i), vectors.get(i).vector());
            }
        }
    }

    /**
     * ES 候选阶段过滤权限，再以 MySQL 当前状态复核。
     * @param query 问题
     * @param limit 结果数量
     * @param scope 服务端权限范围
     * @return 余弦相似度排序结果
     */
    @Override
    public List<Document> search(String query, int limit, RetrievalScope scope) {
        if (!StringUtils.hasText(query) || limit <= 0) return List.of();
        float[] vector = model.embed(query).content().vector();
        validate(vector);
        return searchInternal(query, vector, limit, scope);
    }

    /**
     * 复用相同 ACL 执行 ES BM25。
     * @param query 问题
     * @param limit 结果数量
     * @param scope 服务端权限
     * @return 关键词排序结果
     */
    public List<Document> keywordSearch(String query, int limit, RetrievalScope scope) {
        if (!StringUtils.hasText(query) || limit <= 0) return List.of();
        return searchInternal(query, null, limit, scope);
    }

    /**
     * 构造检索请求，候选逐级扩大以补齐被权威状态过滤的旧投影。
     * @param query 查询文本
     * @param vector 查询向量，关键词检索为空
     * @param limit 结果上限
     * @param scope 权限范围
     * @return 验证后的结果
     */
    private List<Document> searchInternal(String query, float[] vector, int limit, RetrievalScope scope) {
        var effective = scope == null ? RetrievalScope.publicOnly() : scope;
        int requested = Math.min(limit, 100);
        List<Document> verified = List.of();
        for (int count = Math.max(20, requested * 4); count <= 2000; count *= 2) {
            Map<String, Object> body = new HashMap<>();
            body.put("size", count);
            body.put("_source", Map.of("excludes", List.of("embedding")));
            if (vector == null) {
                body.put("query", Map.of("bool", Map.of("must", List.of(Map.of("match", Map.of("content", query))),
                        "filter", List.of(filter(effective)))));
            } else {
                body.put("knn", Map.of("field", "embedding", "query_vector", vector, "k", count,
                        "num_candidates", Math.min(10000, count * 2), "filter", filter(effective)));
            }
            var response = es.request("POST", "/" + es.readIndex() + "/_search", projection.json(body));
            if (response.path("timed_out").asBoolean() || response.path("_shards").path("failed").asInt() > 0)
                throw new IllegalStateException("ES 检索超时或分片失败");
            List<Document> candidates = new ArrayList<>();
            var hits = response.path("hits").path("hits");
            for (var hit : hits) {
                double score = hit.path("_score").asDouble();
                // ES cosine 的 _score=(1+cosine)/2，保持原检索最小 cosine=0.5。
                double similarity = vector == null ? score : 2 * score - 1;
                if (vector != null && similarity < 0.5) continue;
                var source = hit.path("_source");
                var metadata = projection.metadata(source.path("metadata").toString());
                if (vector != null) metadata.put("distance", 1 - similarity);
                candidates.add(new Document(hit.path("_id").asText(), source.path("content").asText(), metadata, similarity));
            }
            verified = projection.verify(candidates, effective, requested);
            if (verified.size() >= requested || hits.size() < count) break;
        }
        return verified;
    }

    /**
     * 构建向量与 BM25 共用的预过滤条件，空部门集合不产生部门分支。
     * @param scope 服务端范围
     * @return ES 查询 DSL
     */
    public static Map<String, Object> filter(RetrievalScope scope) {
        List<Object> branches = new ArrayList<>();
        branches.add(term("visibility", "PUBLIC"));
        if (StringUtils.hasText(scope.tenantId())) {
            branches.add(all(term("visibility", "TENANT"), term("tenantId", scope.tenantId())));
            if (scope.deptIds() == null || !scope.deptIds().isEmpty()) {
                List<Object> dept = new ArrayList<>(List.of(term("visibility", "DEPT"), term("tenantId", scope.tenantId())));
                if (scope.deptIds() != null) dept.add(Map.of("terms", Map.of("metadata.deptId",
                        scope.deptIds().stream().map(String::valueOf).toList())));
                else dept.add(Map.of("bool", Map.of("must_not", List.of(term("deptId", "")))));
                branches.add(Map.of("bool", Map.of("filter", dept)));
            }
            if (StringUtils.hasText(scope.userId())) branches.add(all(term("visibility", "PERSONAL"),
                    term("tenantId", scope.tenantId()), term("owner", scope.userId())));
        }
        return all(term("reviewStatus", "APPROVED"), term("active", true),
                Map.of("bool", Map.of("should", branches, "minimum_should_match", 1)));
    }

    /**
     * 构造精确元数据过滤。
     * @param name 元数据键
     * @param value 值
     * @return 查询条件
     */
    private static Map<String, Object> term(String name, Object value) { return Map.of("term", Map.of("metadata." + name, value)); }

    /**
     * 合并必须满足的条件。
     * @param conditions 条件列表
     * @return 布尔过滤
     */
    private static Map<String, Object> all(Object... conditions) { return Map.of("bool", Map.of("filter", List.of(conditions))); }

    /**
     * 校验模型向量。
     * @param vector 模型输出
     * @return 无返回值
     */
    private void validate(float[] vector) {
        if (vector == null || vector.length != dimensions) throw new IllegalStateException("向量维度不匹配");
        for (float value : vector) if (!Float.isFinite(value)) throw new IllegalStateException("向量含非有限数值");
    }

    /**
     * 登记删除投递。
     * @param sources 来源
     * @return 删除分块数
     */
    @Override
    public int deleteBySources(Collection<String> sources) { return projection.deleteSources(sources); }

    /**
     * 读取有效投影。
     * @return 投影文档，无入参
     */
    @Override
    public List<Document> documents() { return projection.documents(); }
}
