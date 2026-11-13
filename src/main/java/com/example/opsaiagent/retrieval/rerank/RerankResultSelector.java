package com.example.opsaiagent.retrieval.rerank;

import com.example.opsaiagent.knowledge.model.Document;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 对精排响应统一校验、过滤与排序；精排分数不与向量或 BM25 分数混用。 */
final class RerankResultSelector {
    private RerankResultSelector() { }

    /**
     * 校验服务端下标和归一化分数，先过滤再截断，不补足低相关候选。
     * @param results 精排服务返回的结果数组
     * @param originals 当前已授权候选
     * @param topN 数量上限
     * @param minScore 精排分数下限，须用当前模型样本校准
     * @param model 精排模型标识
     * @return 保留原候选评分、带精排评分的副本
     */
    static List<Document> select(JsonNode results, List<Document> originals, int topN,
                                 double minScore, String model) {
        if (!Double.isFinite(minScore) || minScore < 0 || minScore > 1) {
            throw new IllegalArgumentException("精排阈值必须在 0 到 1 之间");
        }
        if (!results.isArray() || results.size() > originals.size()) {
            throw new IllegalArgumentException("精排结果格式或数量异常");
        }
        Set<Integer> seen = new HashSet<>();
        List<Document> accepted = new ArrayList<>();
        for (JsonNode item : results) {
            JsonNode indexNode = item.path("index");
            int index = indexNode.asInt(-1);
            JsonNode scoreNode = item.path("relevance_score");
            double score = scoreNode.asDouble(Double.NaN);
            if (!indexNode.isIntegralNumber() || !indexNode.canConvertToInt()
                    || index < 0 || index >= originals.size() || !seen.add(index)
                    || !scoreNode.isNumber() || !Double.isFinite(score) || score < 0 || score > 1) {
                throw new IllegalArgumentException("精排下标或归一化分数异常");
            }
            if (score < minScore) continue;
            Document original = originals.get(index);
            Document copy = new Document(original.getId(), original.getText(), original.getMetadata(), original.getScore());
            copy.getMetadata().put("rerankScore", score);
            copy.getMetadata().put("rerankModel", model);
            copy.getMetadata().put("score", score);
            accepted.add(copy);
        }
        accepted.sort(Comparator.comparingDouble((Document doc) -> (double) doc.getMetadata().get("rerankScore")).reversed());
        return accepted.stream().limit(Math.max(0, topN)).toList();
    }
}
