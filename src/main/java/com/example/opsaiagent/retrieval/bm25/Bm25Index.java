package com.example.opsaiagent.retrieval.bm25;

import lombok.Getter;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25 内存索引
 * 数据结构：
 * - documents: 原始文档列表
 * - docLengths: 每个文档的 token 数
 * - invertedIndex: token -> (docIdx -> tf)
 * - docFreq: token -> 出现该 token 的文档数
 * - avgDocLength: 平均文档长度
 */
public class Bm25Index {
    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private final List<Document> documents = new ArrayList<>();
    private final List<Integer> docLengths = new ArrayList<>();
    private final Map<String, Map<Integer, Integer>> invertedIndex = new HashMap<>();
    private final Map<String, Integer> docFreq = new HashMap<>();
    private double avgDocLength = 0;

    private final ChineseTokenizer tokenizer = new ChineseTokenizer();

    /**
     * 构建索引
     * @param docs 文档列表
     */
    public void build(List<Document> docs) {
        documents.clear();
        docLengths.clear();
        invertedIndex.clear();
        docFreq.clear();

        int totalLength = 0;

        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            documents.add(doc);

            List<String> tokens = tokenizer.tokenize(doc.getText());
            docLengths.add(tokens.size());
            totalLength += tokens.size();

            // 统计 TF
            Map<String, Integer> termFreq = new HashMap<>();
            for (String token : tokens) {
                termFreq.merge(token, 1, Integer::sum);
            }

            // 写入倒排索引
            for (Map.Entry<String, Integer> e : termFreq.entrySet()) {
                String token = e.getKey();
                invertedIndex.computeIfAbsent(token, k -> new HashMap<>()).put(i, e.getValue());
                docFreq.merge(token, 1, Integer::sum);
            }
        }

        avgDocLength = documents.isEmpty() ? 1.0 : (double) totalLength / documents.size();
    }

    /**
     * BM25 检索
     * @param query 查询文本
     * @param topK 返回条数
     * @return 检索结果列表
     */
    public List<ScoredDocument> search(String query, int topK) {
        List<String> queryTokens = tokenizer.tokenize(query);
        if (queryTokens.isEmpty() || documents.isEmpty()) {
            return List.of();
        }

        Map<Integer, Double> scores = new HashMap<>();
        int N = documents.size();

        for (String token : queryTokens) {
            Map<Integer, Integer> postings = invertedIndex.get(token);
            if (postings == null) continue;

            int df = docFreq.getOrDefault(token, 0);
            double idf = Math.log(1 + (N - df + 0.5) / (df + 0.5));

            for (Map.Entry<Integer, Integer> p : postings.entrySet()) {
                int docIdx = p.getKey();
                int tf = p.getValue();
                int docLen = docLengths.get(docIdx);

                double score = idf * (tf * (K1 + 1))
                        / (tf + K1 * (1 - B + B * docLen / avgDocLength));
                scores.merge(docIdx, score, Double::sum);
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> new ScoredDocument(documents.get(e.getKey()), e.getValue()))
                .toList();
    }

    @Getter
    public static class ScoredDocument {
        private final Document document;
        private final double score;

        public ScoredDocument(Document document, double score) {
            this.document = document;
            this.score = score;
        }
    }

    public int size() {
        return documents.size();
    }
}
