package com.example.opsaiagent.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.knowledge.dto.RetrievalHitResponse;
import com.example.opsaiagent.knowledge.dto.RetrievalTestRequest;
import com.example.opsaiagent.knowledge.dto.RetrievalTestResponse;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.opsaiagent.retrieval.HybridRetriever;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.example.opsaiagent.knowledge.model.Document;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检索测试服务（T18）
 * 混合检索（向量 + BM25 + RRF + Rerank）+ 元数据反查补齐 + 片段高亮 + 展示相关度。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

    /** 片段截取长度 */
    public static final int SNIPPET_LIMIT = 200;

    private static final Pattern WORD_PATTERN = Pattern.compile("[a-zA-Z0-9]+|[\\u4e00-\\u9fa5]+");
    private static final Pattern SHORT_TOPIC_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9]{1,31}");

    private final HybridRetriever hybridRetriever;
    private final KnowledgeDocumentMapper documentMapper;
    private final EmbeddingModel embeddingModel;

    @Value("${ops-agent.retrieval.citations.min-similarity:0.7}")
    private double citationMinSimilarity = 0.7;

    /**
     * 检索测试
     * @param request 请求（query 必填）
     * @return 结构化命中列表
     */
    public RetrievalTestResponse test(RetrievalTestRequest request) {
        String query = request.getQuery() == null ? "" : request.getQuery().trim();
        if (!StringUtils.hasText(query)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "查询文本不能为空");
        }
        int topK = request.getTopK() == null ? 5 : Math.min(Math.max(request.getTopK(), 1), 20);

        List<Document> docs = hybridRetriever.retrieve(query, topK);
        // 已精排的结果直接复用精排结论；仅在未启用精排时使用旧引用校验。
        List<RetrievalHitResponse> hits = toHits(docs, query, topK).stream()
                .filter(hit -> !StringUtils.hasText(request.getLayer())
                        || request.getLayer().equalsIgnoreCase(hit.getLayer()))
                .toList();
        log.info("检索测试: query={}, topK={}, 候选 {}，有效命中 {}", query, topK, docs.size(), hits.size());
        return RetrievalTestResponse.builder()
                .query(query)
                .topK(topK)
                .count(hits.size())
                .hits(hits)
                .build();
    }

    /**
     * 将检索命中的 Document 列表转换为结构化引用（供问答 citations 使用）
     * @param docs 检索命中的文档列表
     * @param query 用户查询
     * @param topK 引用数量上限
     * @return 复用精排结果的引用；未精排的候选使用兼容校验，无相关结果时为空
     */
    public List<RetrievalHitResponse> toHits(List<Document> docs, String query, int topK) {
        if (docs == null || docs.isEmpty() || !StringUtils.hasText(query) || topK <= 0) {
            return List.of();
        }
        List<Document> relevant = relevantCitations(docs, query, topK);
        List<RetrievalHitResponse> hits = new ArrayList<>();
        for (int i = 0; i < relevant.size(); i++) {
            Document doc = relevant.get(i);
            RetrievalHitResponse hit = toHit(doc, query);
            if (hit != null) {
                hits.add(hit);
            }
        }
        return hits;
    }

    /**
     * 校验问答引用与检索测试的候选，不重新检索、不修改模型上下文或原始文档评分。
     * 已通过精排阈值的候选使用精排分数，不再被第二次向量校验或短词规则改变准入结论。
     * 未精排候选保留余弦相似度与短词兼容校验，避免将 BM25 分数当作相关度。
     * @param docs 本轮已召回的候选
     * @param query 用户问题
     * @param topK 引用数量上限
     * @return 保留原顺序的引用候选；向量校验不可用时仅保留精确匹配且评分为空的候选
     */
    private List<Document> relevantCitations(List<Document> docs, String query, int topK) {
        List<Document> candidates = docs.stream()
                .filter(doc -> doc != null && StringUtils.hasText(doc.getText())).toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Document> unscored = candidates.stream().filter(doc -> rerankScore(doc) == null).toList();
        Pattern topic = exactTopicPattern(query);
        List<Embedding> embeddings = List.of();
        try {
            if (!unscored.isEmpty()) {
                if (!Double.isFinite(citationMinSimilarity)
                        || citationMinSimilarity < 0 || citationMinSimilarity > 1) {
                    throw new IllegalStateException("引用相似度阈值必须在 0 到 1 之间");
                }
                List<TextSegment> segments = new ArrayList<>();
                segments.add(TextSegment.from(query));
                unscored.forEach(doc -> segments.add(TextSegment.from(doc.getText())));
                List<Embedding> result = embeddingModel.embedAll(segments).content();
                if (result == null || result.size() != segments.size()) {
                    throw new IllegalStateException("引用校验向量数量不匹配");
                }
                embeddings = result;
            }
        } catch (Exception failure) {
            log.warn("引用向量校验失败，仅保留短技术词精确匹配的文档来源", failure);
        }
        List<Document> accepted = new ArrayList<>();
        int unscoredIndex = 0;
        for (int i = 0; i < candidates.size() && accepted.size() < topK; i++) {
            Document doc = candidates.get(i);
            Double rankedScore = rerankScore(doc);
            if (rankedScore != null) {
                accepted.add(new Document(doc.getId(), doc.getText(), doc.getMetadata(), rankedScore));
                continue;
            }
            double similarity = embeddings.isEmpty() ? Double.NaN
                    : cosineSimilarity(embeddings.get(0), embeddings.get(unscoredIndex + 1));
            unscoredIndex++;
            if (similarity >= citationMinSimilarity || matchesTopic(doc, topic)) {
                Double score = Double.isFinite(similarity) ? similarity : null;
                accepted.add(new Document(doc.getId(), doc.getText(), doc.getMetadata(), score));
            }
        }
        return accepted;
    }

    /**
     * 读取精排层已经校验并准入的独立分数，不把候选的向量或 BM25 分数当作精排分数。
     * @param doc 本轮最终检索结果
     * @return 有效精排分数；未经精排则为空
     */
    private Double rerankScore(Document doc) {
        Map<String, Object> metadata = doc.getMetadata();
        if (metadata == null || !StringUtils.hasText(str(metadata.get("rerankModel")))) return null;
        if (metadata.get("rerankScore") instanceof Number number) {
            double score = number.doubleValue();
            if (Double.isFinite(score) && score >= 0 && score <= 1) return score;
        }
        return null;
    }

    /**
     * 仅为单独输入的短英文技术词构造完整词匹配；完整问题仍使用语义校验。
     * @param query 检索问题
     * @return 不区分大小写且允许中文相邻的匹配式；非单词查询返回空值
     */
    private Pattern exactTopicPattern(String query) {
        String topic = query.strip();
        if (!SHORT_TOPIC_PATTERN.matcher(topic).matches()) return null;
        return Pattern.compile("(?<![a-zA-Z0-9])" + Pattern.quote(topic) + "(?![a-zA-Z0-9])",
                Pattern.CASE_INSENSITIVE);
    }

    /**
     * 用正文与描述性元数据确认主题，不用来源文件名或检索排名充当相关性证据。
     * @param doc 已召回的候选
     * @param topic 单技术词匹配式
     * @return 正文、标题或章节是否完整命中技术词
     */
    private boolean matchesTopic(Document doc, Pattern topic) {
        if (topic == null) return false;
        if (topic.matcher(doc.getText()).find()) return true;
        Map<String, Object> metadata = doc.getMetadata();
        if (metadata == null) return false;
        for (String key : List.of("title", "section")) {
            String value = str(metadata.get(key));
            if (value != null && topic.matcher(value).find()) return true;
        }
        return false;
    }

    /**
     * 计算问题与候选正文的余弦相似度，异常向量不作为引用依据。
     * @param query 问题向量
     * @param document 正文向量
     * @return 余弦相似度；向量无效时返回 NaN
     */
    private double cosineSimilarity(Embedding query, Embedding document) {
        if (query == null || document == null) return Double.NaN;
        float[] left = query.vector();
        float[] right = document.vector();
        if (left.length == 0 || left.length != right.length) return Double.NaN;
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            if (!Float.isFinite(left[i]) || !Float.isFinite(right[i])) return Double.NaN;
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) return Double.NaN;
        return Math.max(-1, Math.min(1, dot / Math.sqrt(leftNorm * rightNorm)));
    }

    /**
     * Document 转命中项：优先使用 DB 文档元数据（真实治理状态），缺失时回退 metadata
     * @param doc 已通过相关性校验的候选，优先展示精排分数，兼容模式下为余弦相似度或空值
     * @param query 用户问题
     * @return 结构化命中项；正文为空时返回空值
     */
    private RetrievalHitResponse toHit(Document doc, String query) {
        if (doc == null || !StringUtils.hasText(doc.getText())) {
            return null;
        }
        Map<String, Object> md = doc.getMetadata() == null ? Map.of() : doc.getMetadata();

        String metadataDocId = str(md.get("docId"));
        String metadataSource = str(md.get("source"));
        String metadataChunkId = str(md.get("chunkId"));
        String metadataTitle = str(md.get("title"));
        String metadataLayer = str(md.get("layer"));
        String metadataStatus = str(md.get("trustStatus"));
        String metadataVersion = str(md.get("version"));
        String metadataSection = str(md.get("section"));

        // 反查知识文档补齐真实元数据
        KnowledgeDocumentEntity dbDoc = resolveDocument(metadataDocId, metadataSource);

        String title = dbDoc != null && StringUtils.hasText(dbDoc.getTitle())
                ? dbDoc.getTitle() : (StringUtils.hasText(metadataTitle) ? metadataTitle : metadataSource);
        String layer = dbDoc != null && StringUtils.hasText(dbDoc.getLayer())
                ? dbDoc.getLayer() : (StringUtils.hasText(metadataLayer) ? metadataLayer : null);
        String status = dbDoc != null && StringUtils.hasText(dbDoc.getTrustStatus())
                ? dbDoc.getTrustStatus() : (StringUtils.hasText(metadataStatus) ? metadataStatus : null);
        Integer version = dbDoc != null && dbDoc.getVersion() != null
                ? dbDoc.getVersion() : parseVersion(metadataVersion);
        String source = dbDoc != null && StringUtils.hasText(dbDoc.getSource())
                ? dbDoc.getSource() : metadataSource;
        Long docId = dbDoc != null ? dbDoc.getId() : parseLong(metadataDocId);
        Long chunkId = parseLong(metadataChunkId);

        String snippet = highlight(doc.getText(), query);

        return RetrievalHitResponse.builder()
                .docId(docId)
                .title(title)
                .layer(layer)
                .trustStatus(status)
                .version(version)
                .source(source)
                .section(StringUtils.hasText(metadataSection) ? metadataSection : null)
                .chunkId(chunkId)
                .snippet(snippet)
                .score(doc.getScore())
                .build();
    }

    /**
     * 反查知识文档：优先 docId，其次按 source + BUILTIN（内置文档）
     */
    private KnowledgeDocumentEntity resolveDocument(String docId, String source) {
        Long id = parseLong(docId);
        if (id != null) {
            KnowledgeDocumentEntity byId = documentMapper.selectById(id);
            if (byId != null) {
                return byId;
            }
        }
        if (StringUtils.hasText(source)) {
            try {
                return documentMapper.selectOne(new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                        .eq(KnowledgeDocumentEntity::getSource, source)
                        .eq(KnowledgeDocumentEntity::getLayer, "BUILTIN")
                        .last("LIMIT 1"));
            } catch (Exception e) {
                log.warn("按 source 反查知识文档失败: {}", source, e);
            }
        }
        return null;
    }

    /**
     * 生成高亮片段：优先整体命中，其次词/2-gram 片段，无命中取前缀
     * @param text 原文
     * @param query 查询
     * @return 高亮片段（mark 标签标记命中词）
     */
    public String highlight(String text, String query) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (query == null || query.isBlank()) {
            return truncate(text, 0, SNIPPET_LIMIT, 0);
        }
        String q = query.trim();
        // 短技术词与准入规则共用完整词边界，避免将 ES 高亮到 Desktop 等单词内部。
        Pattern topic = exactTopicPattern(q);
        if (topic != null) {
            Matcher match = topic.matcher(text);
            return match.find() ? wrap(text, match.start(), match.end())
                    : truncate(text, 0, SNIPPET_LIMIT, 0);
        }
        // 1. 整体命中
        int idx = text.indexOf(q);
        if (idx >= 0) {
            return wrap(text, idx, idx + q.length());
        }
        // 2. 大小写不敏感整体命中
        idx = text.toLowerCase(Locale.ROOT).indexOf(q.toLowerCase(Locale.ROOT));
        if (idx >= 0) {
            return wrap(text, idx, idx + q.length());
        }
        // 3. 词/2-gram 片段命中
        List<String> terms = extractTerms(q);
        int best = -1;
        String bestTerm = null;
        for (String term : terms) {
            int i = text.toLowerCase(Locale.ROOT).indexOf(term.toLowerCase(Locale.ROOT));
            if (i >= 0 && (best < 0 || i < best)) {
                best = i;
                bestTerm = term;
            }
        }
        if (best >= 0) {
            // 命中词/2-gram 后，用查询字符集合向两侧扩展高亮边界，把"数据"扩展为"数据库"
            int from = best;
            int to = best + bestTerm.length();
            Set<Character> chars = queryChars(q);
            while (from > 0 && chars.contains(text.charAt(from - 1))) {
                from--;
            }
            while (to < text.length() && chars.contains(text.charAt(to))) {
                to++;
            }
            return wrap(text, from, to);
        }
        return truncate(text, 0, SNIPPET_LIMIT, 0);
    }

    /**
     * 提取高亮词：英文数字词按整词，中文按字符与连续 2-gram
     */
    List<String> extractTerms(String query) {
        List<String> terms = new ArrayList<>();
        Matcher m = WORD_PATTERN.matcher(query);
        while (m.find()) {
            terms.add(m.group());
        }
        List<String> result = new ArrayList<>();
        for (String t : terms) {
            result.add(t);
            if (t.matches("[\\u4e00-\\u9fa5]{3,}")) {
                // 中文长串补充 2-gram
                for (int i = 0; i < t.length() - 1; i++) {
                    result.add(t.substring(i, i + 2));
                }
            }
        }
        return result.stream().distinct().toList();
    }

    /**
     * 提取查询中的字符集合（排除空白），用于扩展高亮边界
     */
    private Set<Character> queryChars(String query) {
        Set<Character> chars = new HashSet<>();
        for (char c : query.toCharArray()) {
            if (!Character.isWhitespace(c)) {
                chars.add(c);
            }
        }
        return chars;
    }

    /**
     * 以命中为中心截取片段并加 mark 标签
     */
    private String wrap(String text, int start, int end) {
        int center = (start + end) / 2;
        int from = Math.max(0, center - SNIPPET_LIMIT / 2);
        int to = Math.min(text.length(), from + SNIPPET_LIMIT);
        if (to - from < SNIPPET_LIMIT) {
            from = Math.max(0, to - SNIPPET_LIMIT);
        }
        String head = from > 0 ? "…" : "";
        String tail = to < text.length() ? "…" : "";
        return head + text.substring(from, start) + "<mark>"
                + text.substring(start, end) + "</mark>"
                + text.substring(end, to) + tail;
    }

    /**
     * 截断
     */
    private String truncate(String text, int from, int limit, int pad) {
        if (text.length() <= limit) {
            return text;
        }
        String head = from > 0 ? "…" : "";
        String tail = from + limit < text.length() ? "…" : "";
        return head + text.substring(from, Math.min(text.length(), from + limit)) + tail;
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

    private Long parseLong(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseVersion(String s) {
        Long v = parseLong(s);
        return v == null ? null : v.intValue();
    }
}
