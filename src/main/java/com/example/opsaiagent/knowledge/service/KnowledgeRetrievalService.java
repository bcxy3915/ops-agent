package com.example.opsaiagent.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.knowledge.dto.RetrievalHitResponse;
import com.example.opsaiagent.knowledge.dto.RetrievalTestRequest;
import com.example.opsaiagent.knowledge.dto.RetrievalTestResponse;
import com.example.opsaiagent.knowledge.entity.KnowledgeDocumentEntity;
import com.example.opsaiagent.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.opsaiagent.retrieval.HybridRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
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

    private final HybridRetriever hybridRetriever;
    private final KnowledgeDocumentMapper documentMapper;

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
        log.info("检索测试: query={}, topK={}, 命中 {}", query, topK, docs.size());

        List<RetrievalHitResponse> hits = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            RetrievalHitResponse hit = toHit(doc, query, i, topK);
            if (hit == null) {
                continue;
            }
            // 层级过滤（按展示层级的真实值过滤）
            if (StringUtils.hasText(request.getLayer())
                    && !request.getLayer().equalsIgnoreCase(hit.getLayer())) {
                continue;
            }
            hits.add(hit);
        }
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
     * @param topK 检索条数（用于计算位置相关度分数）
     * @return 引用列表；无命中时返回空列表
     */
    public List<RetrievalHitResponse> toHits(List<Document> docs, String query, int topK) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        List<RetrievalHitResponse> hits = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            RetrievalHitResponse hit = toHit(docs.get(i), query, i, topK);
            if (hit != null) {
                hits.add(hit);
            }
        }
        return hits;
    }

    /**
     * Document 转命中项：优先使用 DB 文档元数据（真实治理状态），缺失时回退 metadata
     */
    private RetrievalHitResponse toHit(Document doc, String query, int index, int topK) {
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
        double score = Math.round((topK - index) / (double) topK * 100.0) / 100.0;

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
                .score(score)
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
     * @return 高亮片段（<mark> 标记命中词）
     */
    public String highlight(String text, String query) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (query == null || query.isBlank()) {
            return truncate(text, 0, SNIPPET_LIMIT, 0);
        }
        String q = query.trim();
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
     * 以命中为中心截取片段并加 <mark>
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
