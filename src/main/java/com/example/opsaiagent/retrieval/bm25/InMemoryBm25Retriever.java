package com.example.opsaiagent.retrieval.bm25;

import com.example.opsaiagent.retrieval.KeywordRetriever;
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
    private final Bm25Index index = new Bm25Index();

    /**
     * 初始化索引
     * 使用 ApplicationReadyEvent 确保在 DocumentLoader 完成后执行
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initIndex() {
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
        return index.search(query, topK).stream()
                .map(Bm25Index.ScoredDocument::getDocument)
                .toList();
    }

    /**
     * 重建索引
     */
    @Override
    public void rebuild() {
        try {
            List<Document> docs = jdbcTemplate.query(
                    "SELECT content, metadata FROM vector_store",
                    (rs, rowNum) -> {
                        String id = rs.getString("id");
                        String content = rs.getString("content");
                        String metadataJson = rs.getString("metadata");
                        Map<String, Object> metadata = parseMetadata(metadataJson);
                        return new Document(id, content, metadata);
                    }
            );
            index.build(docs);
            log.info("BM25 索引构建完成，共 {} 个文档", index.size());
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
