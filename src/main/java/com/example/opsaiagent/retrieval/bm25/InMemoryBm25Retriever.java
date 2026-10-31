package com.example.opsaiagent.retrieval.bm25;

import com.example.opsaiagent.rbac.service.DataScope;
import com.example.opsaiagent.retrieval.KeywordRetriever;
import com.example.opsaiagent.retrieval.KnowledgeIndexUpdatedEvent;
import com.example.opsaiagent.retrieval.RetrievalScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(
        name = "ops-agent.retrieval.keyword.type",
        havingValue = "in-memory",
        matchIfMissing = false
)
@RequiredArgsConstructor
public class InMemoryBm25Retriever implements KeywordRetriever {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DataScope dataScope;
    /** 只读快照：重建时整体替换引用，避免检索线程读到半成品索引 */
    private volatile Bm25Index index = new Bm25Index();

    /**
     * 初始化索引
     * 使用 ApplicationReadyEvent 确保在 DocumentLoader 完成后执行
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initIndex() {
        rebuild();
    }

    /**
     * 文档向量入库后刷新索引，避免新文档在关键词检索路径长期缺席。
     *
     * @param event 索引更新事件
     */
    @EventListener
    public void onIndexUpdated(KnowledgeIndexUpdatedEvent event) {
        log.info("检测到知识索引更新，刷新 BM25 索引: docId={}", event.docId());
        rebuild();
    }

    /**
     * 根据查询文本和topK返回文档列表
     * @param query 查询文本
     * @param topK topK
     * @return 文档列表
     */
    @Override
    public List<Document> search(String query, int topK) {
        return search(query, topK, RetrievalScope.current(dataScope));
    }

    /**
     * 按数据范围检索：索引全量构建，查询期过滤 + 过取，避免过滤后结果不足。
     *
     * @param query 查询文本
     * @param topK  topK
     * @param scope 检索范围
     * @return 文档列表
     */
    @Override
    public List<Document> search(String query, int topK, RetrievalScope scope) {
        RetrievalScope effective = scope == null ? RetrievalScope.publicOnly() : scope;
        Bm25Index snapshot = index;
        int fetch = Math.min(snapshot.size(), Math.max(topK, topK * 4));
        return snapshot.search(query, fetch).stream()
                .map(Bm25Index.ScoredDocument::getDocument)
                .filter(doc -> effective.matches(doc.getMetadata()))
                .limit(topK)
                .toList();
    }

    /**
     * 重建索引：先构建独立实例，完成后原子替换，保证检索线程始终读到完整索引
     */
    @Override
    public void rebuild() {
        try {
            List<Document> docs = jdbcTemplate.query(
                    "SELECT id, content, metadata FROM vector_store",
                    (rs, rowNum) -> {
                        String id = rs.getString("id");
                        String content = rs.getString("content");
                        String metadataJson = rs.getString("metadata");
                        Map<String, Object> metadata = parseMetadata(metadataJson);
                        return new Document(id, content, metadata);
                    }
            );
            Bm25Index fresh = new Bm25Index();
            fresh.build(docs);
            index = fresh;
            log.info("BM25 索引构建完成，共 {} 个文档", fresh.size());
        } catch (Exception e) {
            log.error("BM25 索引构建失败", e);
        }
    }

    /**
     * 解析 metadata
     * @param json metadata json
     * @return metadata map
     */
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("metadata 解析失败: {}", json, e);
            return Map.of();
        }
    }
}
