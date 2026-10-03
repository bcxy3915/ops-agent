package com.example.opsaiagent.retrieval.fusion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）融合器
 * 公式：score(d) = Σ 1 / (k + rank_i(d))
 * k 取 60（标准值）
 */
@Slf4j
@Component
public class RrfFuser {

    private static final int K = 60;

    /**
     * 融合多个检索结果
     *
     * @param resultLists 多个检索结果列表
     * @return 融合后的结果列表
     */
    public List<Document> fuse(List<Document>... resultLists) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, Document> docMap = new HashMap<>();

        for (List<Document> results : resultLists) {
            if (results == null) continue;
            for (int i = 0; i < results.size(); i++) {
                Document doc = results.get(i);
                String id = doc.getId();
                if (id == null) {
                    id = String.valueOf(doc.getText().hashCode());
                }
                double contribution = 1.0 / (K + i + 1);
                scores.merge(id, contribution, Double::sum);
                docMap.putIfAbsent(id, doc);
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> docMap.get(e.getKey()))
                .toList();
    }

    /**
     * 融合多个检索结果并限制结果数量
     *
     * @param topK        限制结果数量
     * @param resultLists 多个检索结果列表
     * @return 融合后的结果列表
     */
    public List<Document> fuseAndLimit(int topK, List<Document>... resultLists) {
        List<Document> fused = fuse(resultLists);
        return fused.stream().limit(topK).toList();
    }
}
